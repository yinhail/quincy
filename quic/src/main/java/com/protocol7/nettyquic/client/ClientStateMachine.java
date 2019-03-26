package com.protocol7.nettyquic.client;

import com.google.common.annotations.VisibleForTesting;
import com.protocol7.nettyquic.connection.FrameSender;
import com.protocol7.nettyquic.protocol.TransportError;
import com.protocol7.nettyquic.protocol.Version;
import com.protocol7.nettyquic.protocol.frames.*;
import com.protocol7.nettyquic.protocol.packets.*;
import com.protocol7.nettyquic.streams.StreamManager;
import com.protocol7.nettyquic.tls.ClientTlsSession;
import com.protocol7.nettyquic.tls.aead.AEAD;
import com.protocol7.nettyquic.tls.extensions.TransportParameters;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientStateMachine {

  private final Logger log = LoggerFactory.getLogger(ClientStateMachine.class);

  private ClientState state = ClientState.BeforeInitial;
  private final ClientConnection connection;
  private final DefaultPromise<Void> handshakeFuture =
      new DefaultPromise(GlobalEventExecutor.INSTANCE); // TODO use what event executor?
  private final ClientTlsSession tlsSession;
  private final StreamManager streamManager;
  private final FrameSender frameSender;

  public ClientStateMachine(
      final ClientConnection connection,
      TransportParameters transportParameters,
      final StreamManager streamManager) {
    this.connection = connection;
    this.tlsSession = new ClientTlsSession(transportParameters);
    this.streamManager = streamManager;

    this.frameSender =
        new FrameSender() {
          @Override
          public FullPacket send(final Frame... frames) {
            return connection.sendPacket(frames);
          }

          @Override
          public void closeConnection(
              final TransportError error, final FrameType frameType, final String msg) {
            connection.close(error, frameType, msg);
          }
        };
  }

  public Future<Void> handshake() {
    synchronized (this) {
      // send initial packet
      if (state == ClientState.BeforeInitial) {

        sendInitialPacket();
        state = ClientState.WaitingForServerHello;
        log.info("Client connection state initial sent");
      } else {
        throw new IllegalStateException("Can't handshake in state " + state);
      }
    }
    return handshakeFuture;
  }

  private void sendInitialPacket() {
    final List<Frame> frames = new ArrayList<>();

    int len = 1200;

    final CryptoFrame clientHello = new CryptoFrame(0, tlsSession.startHandshake());
    len -= clientHello.calculateLength();
    frames.add(clientHello);
    frames.add(new PaddingFrame(len));

    connection.sendPacket(
        InitialPacket.create(
            connection.getRemoteConnectionId(),
            connection.getLocalConnectionId(),
            connection.nextSendPacketNumber(),
            connection.getVersion(),
            connection.getToken(),
            frames));
  }

  public void handlePacket(Packet packet) {
    log.info("Client got {} in state {}: {}", packet.getClass().getCanonicalName(), state, packet);

    synchronized (this) { // TODO refactor to make non-synchronized
      // TODO validate connection ID
      if (state == ClientState.WaitingForServerHello) {
        if (packet instanceof InitialPacket) {

          connection.setRemoteConnectionId(packet.getSourceConnectionId().get(), false);

          for (final Frame frame : ((InitialPacket) packet).getPayload().getFrames()) {
            if (frame instanceof CryptoFrame) {
              final CryptoFrame cf = (CryptoFrame) frame;

              final AEAD handshakeAead = tlsSession.handleServerHello(cf.getCryptoData());
              connection.setHandshakeAead(handshakeAead);
              state = ClientState.WaitingForHandshake;
            }
          }
          log.info("Client connection state ready");
        } else if (packet instanceof RetryPacket) {
          final RetryPacket retryPacket = (RetryPacket) packet;
          connection.setRemoteConnectionId(packet.getSourceConnectionId().get(), true);
          connection.resetSendPacketNumber();
          connection.setToken(retryPacket.getRetryToken());

          tlsSession.reset();

          sendInitialPacket();
        } else if (packet instanceof VersionNegotiationPacket) {
          // we only support a single version, so nothing more to do
          log.debug("Incompatible versions, closing connection");
          state = ClientState.Closing;
          connection.closeByPeer().awaitUninterruptibly(); // TODO fix, make async
          log.debug("Connection closed");
          state = ClientState.Closed;
        } else {
          log.warn("Got packet in an unexpected state: {} - {}", state, packet);
        }
      } else if (state == ClientState.WaitingForHandshake) {
        if (packet instanceof HandshakePacket) {
          handleHandshake((HandshakePacket) packet);
        } else {
          log.warn("Got handshake packet in an unexpected state: {} - {}", state, packet);
        }

      } else if (state == ClientState.Ready
          || state == ClientState.Closing
          || state == ClientState.Closed) { // TODO don't allow when closed
        streamManager.onReceivePacket((FullPacket) packet, frameSender);
        for (Frame frame : ((FullPacket) packet).getPayload().getFrames()) {
          handleFrame(frame);
        }
      } else {
        log.warn("Got packet in an unexpected state {} {}", state, packet);
      }
    }
  }

  private void handleHandshake(final HandshakePacket packet) {
    for (final Frame frame : packet.getPayload().getFrames()) {
      if (frame instanceof CryptoFrame) {
        final CryptoFrame cf = (CryptoFrame) frame;

        final Optional<ClientTlsSession.HandshakeResult> result =
            tlsSession.handleHandshake(cf.getCryptoData());

        if (result.isPresent()) {
          connection.setOneRttAead(result.get().getOneRttAead());

          connection.sendPacket(
              HandshakePacket.create(
                  connection.getRemoteConnectionId(),
                  connection.getLocalConnectionId(),
                  connection.nextSendPacketNumber(),
                  Version.CURRENT,
                  new CryptoFrame(0, result.get().getFin())));

          state = ClientState.Ready;
          handshakeFuture.setSuccess(null);
        }
      }
    }
  }

  private void handleFrame(final Frame frame) {
    if (frame instanceof PingFrame) {
      // do nothing, will be acked
    } else if (frame instanceof ConnectionCloseFrame) {
      handlePeerClose();
    }
  }

  private void handlePeerClose() {
    log.debug("Peer closing connection");
    state = ClientState.Closing;
    connection.closeByPeer().awaitUninterruptibly(); // TODO fix, make async
    log.debug("Connection closed");
    state = ClientState.Closed;
  }

  public void closeImmediate(final ConnectionCloseFrame ccf) {
    connection.sendPacket(ccf);

    state = ClientState.Closing;

    state = ClientState.Closed;
  }

  public void closeImmediate() {
    closeImmediate(
        ConnectionCloseFrame.connection(
            TransportError.NO_ERROR.getValue(), 0, "Closing connection"));
  }

  @VisibleForTesting
  protected ClientState getState() {
    return state;
  }
}

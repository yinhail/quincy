package com.protocol7.nettyquic.tls;

import com.protocol7.nettyquic.InboundHandler;
import com.protocol7.nettyquic.PipelineContext;
import com.protocol7.nettyquic.connection.State;
import com.protocol7.nettyquic.protocol.ConnectionId;
import com.protocol7.nettyquic.protocol.frames.CryptoFrame;
import com.protocol7.nettyquic.protocol.packets.FullPacket;
import com.protocol7.nettyquic.protocol.packets.InitialPacket;
import com.protocol7.nettyquic.protocol.packets.Packet;
import com.protocol7.nettyquic.tls.aead.AEAD;
import com.protocol7.nettyquic.tls.aead.InitialAEAD;
import com.protocol7.nettyquic.tls.extensions.TransportParameters;
import java.security.PrivateKey;
import java.util.List;

public class ServerTLSManager implements InboundHandler {

  private ServerTlsSession tlsSession;
  private final TransportParameters transportParameters;
  private final PrivateKey privateKey;
  private final List<byte[]> certificates;

  public ServerTLSManager(
      final ConnectionId connectionId,
      final TransportParameters transportParameters,
      final PrivateKey privateKey,
      final List<byte[]> certificates) {
    this.transportParameters = transportParameters;
    this.privateKey = privateKey;
    this.certificates = certificates;

    resetTlsSession(connectionId);
  }

  public void resetTlsSession(ConnectionId connectionId) {
    this.tlsSession =
        new ServerTlsSession(
            InitialAEAD.create(connectionId.asBytes(), false),
            transportParameters,
            certificates,
            privateKey);
  }

  @Override
  public void onReceivePacket(final Packet packet, final PipelineContext ctx) {
    // TODO check version
    final State state = ctx.getState();
    if (state == State.Started) {
      if (packet instanceof InitialPacket) {
        InitialPacket initialPacket = (InitialPacket) packet;

        if (initialPacket.getToken().isPresent()) {
          CryptoFrame cf = (CryptoFrame) initialPacket.getPayload().getFrames().get(0);

          ServerTlsSession.ServerHelloAndHandshake shah =
              tlsSession.handleClientHello(cf.getCryptoData());

          // sent as initial packet
          ctx.send(new CryptoFrame(0, shah.getServerHello()));

          tlsSession.setHandshakeAead(shah.getHandshakeAEAD());

          // sent as handshake packet
          ctx.send(new CryptoFrame(0, shah.getServerHandshake()));

          tlsSession.setOneRttAead(shah.getOneRttAEAD());

          ctx.setState(State.BeforeReady);
        }
      } else {
        throw new IllegalStateException("Unexpected packet in BeforeInitial: " + packet);
      }
    } else if (state == State.BeforeReady) {
      FullPacket fp = (FullPacket) packet;
      CryptoFrame cryptoFrame = (CryptoFrame) fp.getPayload().getFrames().get(0);
      tlsSession.handleClientFinished(cryptoFrame.getCryptoData());

      ctx.setState(State.Ready);
    }

    ctx.next(packet);
  }

  public AEAD getAEAD(final EncryptionLevel level) {
    return tlsSession.getAEAD(level);
  }

  public boolean available(final EncryptionLevel level) {
    return tlsSession.available(level);
  }
}

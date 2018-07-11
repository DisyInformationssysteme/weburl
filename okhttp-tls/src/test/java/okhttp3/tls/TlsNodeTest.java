/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.tls;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import okhttp3.Handshake;
import okhttp3.internal.Util;
import okio.ByteString;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TlsNodeTest {
  private ExecutorService executorService;
  private ServerSocket serverSocket;

  @Before public void setUp() {
    executorService = Executors.newCachedThreadPool();
  }

  @After public void tearDown() {
    executorService.shutdown();
    Util.closeQuietly(serverSocket);
  }

  @Test public void clientAndServer() throws Exception {
    HeldCertificate clientRoot = new HeldCertificate.Builder()
        .certificateAuthority(1)
        .build();
    HeldCertificate clientIntermediate = new HeldCertificate.Builder()
        .certificateAuthority(0)
        .issuedBy(clientRoot)
        .build();
    HeldCertificate clientCertificate = new HeldCertificate.Builder()
        .issuedBy(clientIntermediate)
        .build();

    HeldCertificate serverRoot = new HeldCertificate.Builder()
        .certificateAuthority(1)
        .build();
    HeldCertificate serverIntermediate = new HeldCertificate.Builder()
        .certificateAuthority(0)
        .issuedBy(serverRoot)
        .build();
    HeldCertificate serverCertificate = new HeldCertificate.Builder()
        .issuedBy(serverIntermediate)
        .build();

    TlsNode server = new TlsNode.Builder()
        .addTrustedCertificate(clientRoot.certificate())
        .heldCertificate(serverCertificate, serverIntermediate.certificate())
        .build();

    TlsNode client = new TlsNode.Builder()
        .addTrustedCertificate(serverRoot.certificate())
        .heldCertificate(clientCertificate, clientIntermediate.certificate())
        .build();

    InetSocketAddress serverAddress = startTlsServer();
    Future<Handshake> serverHandshakeFuture = doServerHandshake(server);
    Future<Handshake> clientHandshakeFuture = doClientHandshake(client, serverAddress);

    Handshake serverHandshake = serverHandshakeFuture.get();
    assertEquals(serverHandshake.peerCertificates(),
        Arrays.asList(clientCertificate.certificate(), clientIntermediate.certificate()));
    assertEquals(serverHandshake.localCertificates(),
        Arrays.asList(serverCertificate.certificate(), serverIntermediate.certificate()));

    Handshake clientHandshake = clientHandshakeFuture.get();
    assertEquals(clientHandshake.peerCertificates(),
        Arrays.asList(serverCertificate.certificate(), serverIntermediate.certificate()));
    assertEquals(clientHandshake.localCertificates(),
        Arrays.asList(clientCertificate.certificate(), clientIntermediate.certificate()));

  }

  @Test public void keyManager() {
    HeldCertificate root = new HeldCertificate.Builder()
        .certificateAuthority(1)
        .build();
    HeldCertificate intermediate = new HeldCertificate.Builder()
        .certificateAuthority(0)
        .issuedBy(root)
        .build();
    HeldCertificate certificate = new HeldCertificate.Builder()
        .issuedBy(intermediate)
        .build();

    TlsNode tlsNode = new TlsNode.Builder()
        .heldCertificate(certificate, intermediate.certificate())
        .build();
    assertPrivateKeysEquals(certificate.keyPair().getPrivate(),
        tlsNode.keyManager().getPrivateKey("private"));
    assertEquals(Arrays.asList(certificate.certificate(), intermediate.certificate()),
        Arrays.asList(tlsNode.keyManager().getCertificateChain("private")));
  }

  private InetSocketAddress startTlsServer() throws IOException {
    ServerSocketFactory serverSocketFactory = ServerSocketFactory.getDefault();
    serverSocket = serverSocketFactory.createServerSocket();
    InetAddress serverAddress = InetAddress.getByName("localhost");
    serverSocket.bind(new InetSocketAddress(serverAddress, 0), 50);
    return new InetSocketAddress(serverAddress, serverSocket.getLocalPort());
  }

  private Future<Handshake> doServerHandshake(final TlsNode server) {
    return executorService.submit(new Callable<Handshake>() {
      @Override public Handshake call() throws Exception {
        Socket rawSocket = null;
        SSLSocket sslSocket = null;
        try {
          rawSocket = serverSocket.accept();
          sslSocket = (SSLSocket) server.sslSocketFactory().createSocket(rawSocket,
              rawSocket.getInetAddress().getHostAddress(), rawSocket.getPort(),
              true /* autoClose */);
          sslSocket.setUseClientMode(false);
          sslSocket.setWantClientAuth(true);
          sslSocket.startHandshake();
          return Handshake.get(sslSocket.getSession());
        } finally {
          Util.closeQuietly(rawSocket);
          Util.closeQuietly(sslSocket);
        }
      }
    });
  }

  private Future<Handshake> doClientHandshake(
      final TlsNode client, final InetSocketAddress serverAddress) {
    return executorService.submit(new Callable<Handshake>() {
      @Override public Handshake call() throws Exception {
        Socket rawSocket = SocketFactory.getDefault().createSocket();
        rawSocket.connect(serverAddress);
        SSLSocket sslSocket = null;
        try {
          sslSocket = (SSLSocket) client.sslSocketFactory().createSocket(rawSocket,
              rawSocket.getInetAddress().getHostAddress(), rawSocket.getPort(),
              true /* autoClose */);
          sslSocket.startHandshake();
          return Handshake.get(sslSocket.getSession());
        } finally {
          Util.closeQuietly(rawSocket);
          Util.closeQuietly(sslSocket);
        }
      }
    });
  }

  private void assertPrivateKeysEquals(PrivateKey expected, PrivateKey actual) {
    assertEquals(ByteString.of(expected.getEncoded()), ByteString.of(actual.getEncoded()));
  }
}
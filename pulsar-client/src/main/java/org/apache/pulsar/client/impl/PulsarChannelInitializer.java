/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.impl;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.pulsar.client.api.AuthenticationDataProvider;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.client.util.ObjectCache;
import org.apache.pulsar.common.protocol.ByteBufPair;
import org.apache.pulsar.common.protocol.Commands;
import org.apache.pulsar.common.util.SecurityUtility;
import org.apache.pulsar.common.util.keystoretls.NettySSLContextAutoRefreshBuilder;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PulsarChannelInitializer extends ChannelInitializer<SocketChannel> {

    public static final String TLS_HANDLER = "tls";

    private final Supplier<ClientCnx> clientCnxSupplier;
    @Getter
    private final boolean tlsEnabled;
    private final boolean tlsEnabledWithKeyStore;

    private final Supplier<SslContext> sslContextSupplier;
    private NettySSLContextAutoRefreshBuilder nettySSLContextAutoRefreshBuilder;

    private static final long TLS_CERTIFICATE_CACHE_MILLIS = TimeUnit.MINUTES.toMillis(1);

    public PulsarChannelInitializer(ClientConfigurationData conf, Supplier<ClientCnx> clientCnxSupplier)
            throws Exception {
        super();
        this.clientCnxSupplier = clientCnxSupplier;
        this.tlsEnabled = conf.isUseTls();
        this.tlsEnabledWithKeyStore = conf.isUseKeyStoreTls();

        if (tlsEnabled) {
            if (tlsEnabledWithKeyStore) {
                AuthenticationDataProvider authData1 = conf.getAuthentication().getAuthData();

                nettySSLContextAutoRefreshBuilder = new NettySSLContextAutoRefreshBuilder(
                            conf.getSslProvider(),
                            conf.isTlsAllowInsecureConnection(),
                            conf.getTlsTrustStoreType(),
                            conf.getTlsTrustStorePath(),
                            conf.getTlsTrustStorePassword(),
                            conf.getTlsCiphers(),
                            conf.getTlsProtocols(),
                            TLS_CERTIFICATE_CACHE_MILLIS,
                            authData1);
            }

            sslContextSupplier = new ObjectCache<SslContext>(() -> {
                try {
                    // Set client certificate if available
                    AuthenticationDataProvider authData = conf.getAuthentication().getAuthData();
                    if (authData.hasDataForTls()) {
                        return authData.getTlsTrustStoreStream() == null
                                ? SecurityUtility.createNettySslContextForClient(conf.isTlsAllowInsecureConnection(),
                                        conf.getTlsTrustCertsFilePath(),
                                        authData.getTlsCertificates(), authData.getTlsPrivateKey())
                                : SecurityUtility.createNettySslContextForClient(conf.isTlsAllowInsecureConnection(),
                                        authData.getTlsTrustStoreStream(),
                                        authData.getTlsCertificates(), authData.getTlsPrivateKey());
                    } else {
                        return SecurityUtility.createNettySslContextForClient(conf.isTlsAllowInsecureConnection(),
                                conf.getTlsTrustCertsFilePath());
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create TLS context", e);
                }
            }, TLS_CERTIFICATE_CACHE_MILLIS, TimeUnit.MILLISECONDS);
        } else {
            sslContextSupplier = null;
        }
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {

        // Setup channel except for the SsHandler for TLS enabled connections

        ch.pipeline().addLast("ByteBufPairEncoder", tlsEnabled ? ByteBufPair.COPYING_ENCODER : ByteBufPair.ENCODER);

        ch.pipeline().addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                Commands.DEFAULT_MAX_MESSAGE_SIZE + Commands.MESSAGE_SIZE_FRAME_PADDING, 0, 4, 0, 4));
        ch.pipeline().addLast("handler", clientCnxSupplier.get());
    }

    CompletableFuture<Channel> initTls(Channel ch, InetSocketAddress sniHost) {
        if (!tlsEnabled) {
            throw new IllegalStateException("TLS is not enabled in client configuration");
        }
        CompletableFuture<Channel> initTlsFuture = new CompletableFuture<>();
        ch.eventLoop().execute(() -> {
            try {
                SslHandler handler = tlsEnabledWithKeyStore
                        ? new SslHandler(nettySSLContextAutoRefreshBuilder.get()
                                .createSSLEngine(sniHost.getHostString(), sniHost.getPort()))
                        : sslContextSupplier.get().newHandler(ch.alloc(), sniHost.getHostString(), sniHost.getPort());
                ch.pipeline().addFirst(TLS_HANDLER, handler);
                initTlsFuture.complete(ch);
            } catch (Throwable t) {
                initTlsFuture.completeExceptionally(t);
            }
        });

        return initTlsFuture;
    }
}


/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.nodex.core.net;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.ChannelGroupFutureListener;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioSocketChannel;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.nodex.core.Nodex;
import org.nodex.core.NodexInternal;
import org.nodex.core.ThreadSourceUtils;
import org.nodex.core.buffer.Buffer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NetServer extends NetBase {
  private ServerBootstrap bootstrap;
  private Map<Channel, NetSocket> socketMap = new ConcurrentHashMap();
  private final NetConnectHandler connectCallback;
  private Map<String, Object> connectionOptions = new HashMap();
  private ChannelGroup serverChannelGroup;
  private boolean listening;

  protected ClientAuth clientAuth = ClientAuth.NONE;

  protected enum ClientAuth {
    NONE, REQUEST, REQUIRED
  }

  private NetServer(NetConnectHandler connectHandler) {
    this.connectCallback = connectHandler;

    //Defaults
    connectionOptions.put("child.tcpNoDelay", true);
    connectionOptions.put("child.keepAlive", true);
    //TODO reusAddress should be configurable
    connectionOptions.put("reuseAddress", true); //Not child since applies to the acceptor socket
  }

  public static NetServer createServer(NetConnectHandler connectCallback) {
    return new NetServer(connectCallback);
  }

  public NetServer setSSL(boolean ssl) {
    this.ssl = ssl;
    return this;
  }

  public NetServer setKeyStorePath(String path) {
    this.keyStorePath = path;
    return this;
  }

  public NetServer setKeyStorePassword(String pwd) {
    this.keyStorePassword = pwd;
    return this;
  }
  public NetServer setTrustStorePath(String path) {
    this.trustStorePath = path;
    return this;
  }

  public NetServer setTrustStorePassword(String pwd) {
    this.trustStorePassword = pwd;
    return this;
  }

  public NetServer setClientAuthRequired(boolean required) {
    clientAuth = required ? ClientAuth.REQUIRED : ClientAuth.NONE;
    return this;
  }

  public NetServer setTcpNoDelay(boolean tcpNoDelay) {
    connectionOptions.put("child.tcpNoDelay", tcpNoDelay);
    return this;
  }

  public NetServer setSendBufferSize(int size) {
    connectionOptions.put("child.sendBufferSize", size);
    return this;
  }

  public NetServer setReceiveBufferSize(int size) {
    connectionOptions.put("child.receiveBufferSize", size);
    return this;
  }

  public NetServer setKeepAlive(boolean keepAlive) {
    connectionOptions.put("child.keepAlive", keepAlive);
    return this;
  }

  public NetServer setReuseAddress(boolean reuse) {
    connectionOptions.put("child.reuseAddress", reuse);
    return this;
  }

  public NetServer setSoLinger(boolean linger) {
    connectionOptions.put("child.soLinger", linger);
    return this;
  }

  public NetServer setTrafficClass(int trafficClass) {
    connectionOptions.put("child.trafficClass", trafficClass);
    return this;
  }

  public NetServer listen(int port) {
    return listen(port, "0.0.0.0");
  }

  public NetServer listen(int port, String host) {
    if (bootstrap == null) {
      serverChannelGroup = new DefaultChannelGroup("nodex-acceptor-channels");

      ChannelFactory factory =
          new NioServerSocketChannelFactory(
              NodexInternal.instance.getAcceptorPool(),
              NodexInternal.instance.getWorkerPool());
      bootstrap = new ServerBootstrap(factory);

      checkSSL();

      bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
        public ChannelPipeline getPipeline() {
          ChannelPipeline pipeline = Channels.pipeline();
          if (ssl) {
            SSLEngine engine = context.createSSLEngine();
            engine.setUseClientMode(false);
            System.out.println("client auth? " + clientAuth);
            switch (clientAuth) {
              case REQUEST: {
                engine.setWantClientAuth(true);
                break;
              } case REQUIRED: {
                engine.setNeedClientAuth(true);
                break;
              } case NONE: {
                engine.setNeedClientAuth(false);
                break;
              }
            }
            pipeline.addLast("ssl", new SslHandler(engine));
          }
          pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());  // For large file / sendfile support
          pipeline.addLast("handler", new ServerHandler());
          return pipeline;
        }
      });
    }

    if (listening) {
      throw new IllegalStateException("Server already listening");
    }
    try {
      bootstrap.setOptions(connectionOptions);
      //TODO - currently bootstrap.bind is blocking - need to make it non blocking by not using bootstrap directly
      Channel serverChannel = bootstrap.bind(new InetSocketAddress(InetAddress.getByName(host), port));
      serverChannelGroup.add(serverChannel);
      System.out.println("Net server listening on " + host + ":" + port);
    } catch (UnknownHostException e) {
      e.printStackTrace();
    }
    listening = true;
    return this;
  }

  public void close() {
    close(null);
  }

  public void close(final Runnable done) {
    for (NetSocket sock : socketMap.values()) {
      sock.close();
    }
    if (done != null) {
      final String contextID = Nodex.instance.getContextID();
      serverChannelGroup.close().addListener(new ChannelGroupFutureListener() {
        public void operationComplete(ChannelGroupFuture channelGroupFuture) throws Exception {

          Runnable runner = new Runnable() {
            public void run() {
             listening = false;
             done.run();
            }
          };

          if (contextID == null) {
            //Called from thread outside event loop, e.g. main thread
            runner.run();
          } else {
            NodexInternal.instance.executeOnContext(contextID, runner);
          }
        }
      });
    }
  }

  private class ServerHandler extends SimpleChannelHandler {

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final String contextID = NodexInternal.instance.createContext(ch.getWorker());
      ThreadSourceUtils.runOnCorrectThread(ch, new Runnable() {
        public void run() {
          NetSocket sock = new NetSocket(ch, contextID, Thread.currentThread());
          socketMap.put(ch, sock);
          NodexInternal.instance.setContextID(contextID);
          connectCallback.onConnect(sock);
        }
      });
    }

    @Override
    public void channelInterestChanged(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final NetSocket sock = socketMap.get(ch);
      ChannelState state = e.getState();
      if (state == ChannelState.INTEREST_OPS) {
        ThreadSourceUtils.runOnCorrectThread(ch, new Runnable() {
          public void run() {
            sock.handleInterestedOpsChanged();
          }
        });
      }
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final NetSocket sock = socketMap.remove(ch);
      if (sock != null) {
        ThreadSourceUtils.runOnCorrectThread(ch, new Runnable() {
          public void run() {
            sock.handleClosed();
            NodexInternal.instance.destroyContext(sock.getContextID());
          }
        });
      }
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
      Channel ch = e.getChannel();
      NetSocket sock = socketMap.get(ch);
      ChannelBuffer buff = (ChannelBuffer) e.getMessage();
      sock.handleDataReceived(new Buffer(buff.slice()));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final NetSocket sock = socketMap.get(ch);
      ch.close();
      final Throwable t = e.getCause();
      if (sock != null && t instanceof Exception) {
        ThreadSourceUtils.runOnCorrectThread(ch, new Runnable() {
          public void run() {
            sock.handleException((Exception) t);
          }
        });
      } else {
        t.printStackTrace();
      }
    }
  }
}
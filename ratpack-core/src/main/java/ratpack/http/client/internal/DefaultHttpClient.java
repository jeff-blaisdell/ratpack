/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.http.client.internal;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import ratpack.exec.*;
import ratpack.func.Action;
import ratpack.func.Actions;
import ratpack.http.Headers;
import ratpack.http.MutableHeaders;
import ratpack.http.Status;
import ratpack.http.client.HttpClient;
import ratpack.http.client.ReceivedResponse;
import ratpack.http.client.RequestSpec;
import ratpack.http.internal.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.net.URI;

import static ratpack.util.ExceptionUtils.uncheck;

public class DefaultHttpClient implements HttpClient {

  private final ExecController execController;
  private final ByteBufAllocator byteBufAllocator;
  private final int maxContentLengthBytes;

  public DefaultHttpClient(ExecController execController, ByteBufAllocator byteBufAllocator, int maxContentLengthBytes) {
    this.execController = execController;
    this.byteBufAllocator = byteBufAllocator;
    this.maxContentLengthBytes = maxContentLengthBytes;
  }

  @Override
  public Promise<ReceivedResponse> get(Action<? super RequestSpec> requestConfigurer) {
    return request(requestConfigurer);
  }

  private static class Post implements Action<RequestSpec> {
    @Override
    public void execute(RequestSpec requestSpec) throws Exception {
      requestSpec.method("POST");
    }
  }

  public Promise<ReceivedResponse> post(Action<? super RequestSpec> action) {
    return request(Actions.join(new Post(), action));
  }

  @Override
  public Promise<ReceivedResponse> request(final Action<? super RequestSpec> requestConfigurer) {

    final ExecControl execControl = execController.getControl();
    final Execution execution = execControl.getExecution();
    final EventLoopGroup eventLoopGroup = execController.getEventLoopGroup();

    final MutableHeaders headers = new NettyHeadersBackedMutableHeaders(new DefaultHttpHeaders());
    final RequestSpecBacking requestSpecBacking = new RequestSpecBacking(headers, byteBufAllocator);

    try {
      requestConfigurer.execute(requestSpecBacking.asSpec());
    } catch (Exception e) {
      throw uncheck(e);
    }

    final URI uri = requestSpecBacking.getUrl();

    String scheme = uri.getScheme();
    boolean useSsl = false;
    if (scheme.equals("https")) {
      useSsl = true;
    } else if (!scheme.equals("http")) {
      throw new IllegalArgumentException(String.format("URL '%s' is not a http url", uri.toString()));
    }
    final boolean finalUseSsl = useSsl;

    final String host = uri.getHost();
    final int port = uri.getPort() < 0 ? (useSsl ? 443 : 80) : uri.getPort();

    return execController.getControl().promise(new Action<Fulfiller<ReceivedResponse>>() {
      @Override
      public void execute(final Fulfiller<ReceivedResponse> fulfiller) throws Exception {
        final Bootstrap b = new Bootstrap();
        b.group(eventLoopGroup)
          .channel(NioSocketChannel.class)
          .handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
              ChannelPipeline p = ch.pipeline();

              if (finalUseSsl) {
                SSLEngine engine = SSLContext.getDefault().createSSLEngine();
                engine.setUseClientMode(true);
                p.addLast("ssl", new SslHandler(engine));
              }

              p.addLast("codec", new HttpClientCodec());
              p.addLast("aggregator", new HttpObjectAggregator(maxContentLengthBytes));
              p.addLast("handler", new SimpleChannelInboundHandler<HttpObject>() {
                @Override
                public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
                  if (msg instanceof FullHttpResponse) {
                    final FullHttpResponse response = (FullHttpResponse) msg;
                    final Headers headers = new NettyHeadersBackedHeaders(response.headers());
                    String contentType = headers.get(HttpHeaderConstants.CONTENT_TYPE.toString());
                    ByteBuf responseBuffer = initBufferReleaseOnExecutionClose(response.content(), execution);
                    final ByteBufBackedTypedData typedData = new ByteBufBackedTypedData(responseBuffer, DefaultMediaType.get(contentType));

                    final Status status = new DefaultStatus(response.getStatus().code(), response.getStatus().reasonPhrase());
                    fulfiller.success(new DefaultReceivedResponse(status, headers, typedData));
                  }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                  ctx.close();
                  fulfiller.error(cause);
                }
              });
            }
          });

        b.connect(host, port).addListener(new ChannelFutureListener() {
          @Override
          public void operationComplete(ChannelFuture future) throws Exception {
            if (future.isSuccess()) {

              String fullPath = getFullPath(uri);
              FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(requestSpecBacking.getMethod()), fullPath, requestSpecBacking.getBody());
              if (headers.get(HttpHeaders.Names.HOST) == null) {
                headers.set(HttpHeaders.Names.HOST, host);
              }
              headers.set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
              int contentLength = request.content().readableBytes();
              if (contentLength > 0) {
                headers.set(HttpHeaders.Names.CONTENT_LENGTH, Integer.toString(contentLength, 10));
              }

              HttpHeaders requestHeaders = request.headers();

              for (String name : headers.getNames()) {
                requestHeaders.set(name, headers.getAll(name));
              }

              future.channel().writeAndFlush(request).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                  if (!future.isSuccess()) {
                    future.channel().close();
                    fulfiller.error(future.cause());
                  }
                }
              });
            } else {
              future.channel().close();
              fulfiller.error(future.cause());
            }
          }
        });
      }
    });
  }

  private static ByteBuf initBufferReleaseOnExecutionClose(final ByteBuf responseBuffer, Execution execution) {
    execution.onCleanup(new AutoCloseable() {
      @Override
      public void close() {
        responseBuffer.release();
      }
    });
    return responseBuffer.retain();
  }

  private static String getFullPath(URI uri) {
    StringBuilder sb = new StringBuilder(uri.getRawPath());
    String query = uri.getRawQuery();
    if (query != null) {
      sb.append("?").append(query);
    }

    return sb.toString();
  }

}

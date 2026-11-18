package ro.pricechecker.fetch;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A {@link Transport} that never touches the network (T-3): scripted replies, recorded requests.
 */
final class StubTransport implements Transport {

  /** One recorded request. */
  record Request(URI url, String userAgent, Duration timeout) {}

  /** One scripted reply: a response, or an exception to throw. */
  @FunctionalInterface
  interface Reply {
    Response answer(URI url) throws IOException, InterruptedException;
  }

  final List<Request> requests = new ArrayList<>();
  private final Deque<Reply> replies = new ArrayDeque<>();
  private Reply fallback = url -> ok("<html>page</html>");

  static Response ok(String body) {
    return new Response(200, Optional.empty(), body);
  }

  static Response status(int status) {
    return new Response(status, Optional.empty(), "");
  }

  /** A 429, with the given {@code Retry-After} header value when non-null. */
  static Response tooMany(String retryAfter) {
    return new Response(429, Optional.empty(), "", Optional.ofNullable(retryAfter));
  }

  static Response redirect(int status, String location) {
    return new Response(status, Optional.of(location), "");
  }

  StubTransport then(Reply reply) {
    replies.add(reply);
    return this;
  }

  StubTransport then(Response response) {
    return then(url -> response);
  }

  StubTransport always(Reply reply) {
    fallback = reply;
    return this;
  }

  StubTransport always(Response response) {
    return always(url -> response);
  }

  @Override
  public Response get(URI url, String userAgent, Duration timeout)
      throws IOException, InterruptedException {
    requests.add(new Request(url, userAgent, timeout));
    Reply reply = replies.isEmpty() ? fallback : replies.poll();
    return reply.answer(url);
  }
}

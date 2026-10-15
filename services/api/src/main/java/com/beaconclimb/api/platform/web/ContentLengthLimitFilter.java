package com.beaconclimb.api.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request body limit (TM-25, BC-0122). A declared {@code Content-Length} above {@code
 * beacon.http.max-body-bytes} is answered 413 PAYLOAD_TOO_LARGE before a byte of body is read. A
 * body without a usable length (chunked) is read through a bounded stream that fails with {@link
 * PayloadTooLargeException} at the limit; the exception handler turns that into the same 413, and
 * this filter does so itself if the failure escapes the chain uncommitted. Tomcat's own {@code
 * max-http-form-post-size} does not apply to JSON bodies read by Jackson, hence the filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class ContentLengthLimitFilter extends OncePerRequestFilter {

  private final long maxBodyBytes;
  private final ErrorResponseWriter errors;

  public ContentLengthLimitFilter(HttpProperties properties, ErrorResponseWriter errors) {
    this.maxBodyBytes = properties.maxBodyBytes();
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > maxBodyBytes) {
      reject(request, response);
      return;
    }
    try {
      chain.doFilter(new BoundedRequest(request, maxBodyBytes), response);
    } catch (PayloadTooLargeException e) {
      reject(request, response);
    }
  }

  private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
    errors.write(
        request,
        response,
        HttpStatus.CONTENT_TOO_LARGE,
        ErrorCode.PAYLOAD_TOO_LARGE,
        "Request body is too large",
        Map.of());
  }

  /** Wraps the input stream and reader so reads past the limit fail. */
  static final class BoundedRequest extends HttpServletRequestWrapper {

    private final long limit;
    private BoundedInputStream stream;
    private BufferedReader reader;

    BoundedRequest(HttpServletRequest request, long limit) {
      super(request);
      this.limit = limit;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (stream == null) {
        stream = new BoundedInputStream(super.getInputStream(), limit);
      }
      return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      if (reader == null) {
        String encoding = getCharacterEncoding();
        Charset charset =
            encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
        reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
      }
      return reader;
    }
  }

  static final class BoundedInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long limit;
    private long count;

    BoundedInputStream(ServletInputStream delegate, long limit) {
      this.delegate = delegate;
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      if (value >= 0) {
        account(1);
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = delegate.read(buffer, offset, length);
      if (read > 0) {
        account(read);
      }
      return read;
    }

    private void account(int bytes) throws IOException {
      count += bytes;
      if (count > limit) {
        throw new PayloadTooLargeException(limit);
      }
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      delegate.setReadListener(listener);
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}

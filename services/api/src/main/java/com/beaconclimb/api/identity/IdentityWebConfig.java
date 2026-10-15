package com.beaconclimb.api.identity;

import com.beaconclimb.api.platform.web.ApiException;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets controllers declare an {@link AuthenticatedUser} parameter, resolved from the request
 * attribute set by {@link BearerAuthenticationFilter}. A protected handler reached without it
 * (which the filter prevents) fails closed with 401.
 */
@Configuration
class IdentityWebConfig implements WebMvcConfigurer {

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new AuthenticatedUserArgumentResolver());
  }

  static final class AuthenticatedUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
      return AuthenticatedUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory) {
      Object user =
          webRequest.getAttribute(AuthenticatedUser.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
      if (user instanceof AuthenticatedUser authenticated) {
        return authenticated;
      }
      throw ApiException.unauthenticated();
    }
  }
}

package com.github.hlvx.rest.servers;

import com.github.hlvx.rest.authentication.AuthProvider;
import com.github.hlvx.rest.beans.ErrorBean;
import com.github.hlvx.rest.exceptions.HTTPException;
import com.github.hlvx.rest.resources.SwaggerResource;
import com.zandero.rest.RestBuilder;
import com.zandero.rest.RestRouter;
import com.zandero.rest.context.ContextProvider;
import com.zandero.rest.exception.ExceptionHandler;
import com.zandero.rest.exception.ExecuteException;
import com.zandero.rest.injection.InjectionProvider;
import io.swagger.v3.jaxrs2.Reader;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.vertx.core.AsyncResult;
import io.vertx.core.Closeable;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Validator;
import javax.validation.constraints.NotNull;
import java.util.HashSet;
import java.util.Set;

/**
 * Create and configure a REST HTTP server
 * @author AlexMog
 */
public class RestHTTPServer implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(RestHTTPServer.class);
    private Router router;
    private HttpServer httpServer;
    private Object[] services;
    private ContextProvider<?>[] providers;
    private InjectionProvider injectWith;
    private AuthProvider authenticationProvider;
    private Validator validateWith;
    private CorsConfig corsConfig;
    private final Vertx vertx;
    private Info openAPIInfo;

    public RestHTTPServer(Vertx vertx) {
        this.vertx = vertx;
    }

    public RestHTTPServer withSwagger(Info swaggerInfo) {
        openAPIInfo = swaggerInfo;
        return this;
    }

    private void init() {
        if (router != null) return;
        router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        if (authenticationProvider != null) {
            router.route().handler(context -> {
                authenticationProvider.authorize(context, userResponse -> {
                    if (userResponse.succeeded()) context.setUser(userResponse.result());
                    else if (userResponse.cause() instanceof HTTPException) throw (HTTPException) userResponse.cause();
					else throw new RuntimeException(userResponse.cause());
                    context.next();
                });
            });
        }

        router.errorHandler(500, ctx -> {
            if (ctx.failure() instanceof HTTPException) {
                HTTPException ex = (HTTPException) ctx.failure();
                ctx.response()
                        .setStatusCode(ex.getCode())
                        .putHeader("Content-Type", "application/json;charset=UTF-8")
                        .setStatusMessage("HTTP " + ex.getCode() + " " + ex.getMessage())
                        .end(Json.encode(new ErrorBean(ex.getCode(), ex.getMessage())));
            } else {
                logger.error("Internal error catched", ctx.failure());
                ctx.response()
                        .setStatusCode(500)
                        .putHeader("Content-Type", "application/json;charset=UTF-8")
                        .setStatusMessage("HTTP 500 Internal Server Error")
                        .end(Json.encode(new ErrorBean(500, "Internal Server Error")));
            }
        });

        RestBuilder builder = new RestBuilder(router);

        Set<Class<?>> servicesSet = new HashSet<>();
        for (Object service : services) {
            builder.register(service);
            servicesSet.add(service.getClass());
        }

        if (openAPIInfo != null) {
            OpenAPI openAPI = new OpenAPI();
            openAPI.info(openAPIInfo);
            builder.register(
                    new SwaggerResource(new Reader(openAPI).read(servicesSet)));
        }


        if (providers != null)
            for (ContextProvider<?> provider : providers) builder.addProvider(provider);
        if (injectWith != null) builder.injectWith(injectWith);
        if (validateWith != null) builder.validateWith(validateWith);
        if (corsConfig != null) builder.enableCors(corsConfig.getAllowedOriginPattern(),
                corsConfig.isAllowCredentials(), corsConfig.getMaxAge(),
                corsConfig.getAllowedHeaders(), corsConfig.getHttpMethods());

        builder
            .errorHandler(new ExceptionHandler<Throwable>() {
                @Override
                public void write(Throwable result, HttpServerRequest request, HttpServerResponse response) {
                    response.putHeader("Content-Type", "application/json;charset=UTF-8");
                    ErrorBean bean;
                    if (result instanceof HTTPException) {
                        HTTPException ex = (HTTPException) result;
                        response.setStatusCode(ex.getCode());
                        response.setStatusMessage("HTTP " + ex.getCode() + " " + ex.getMessage());
                        bean = new ErrorBean(ex.getCode(), ex.getMessage());
                    } else if (result instanceof ExecuteException) {
                        ExecuteException ex = (ExecuteException) result;
                        response.setStatusCode(ex.getStatusCode());
                        response.setStatusMessage(ex.getMessage());
                        bean = new ErrorBean(ex.getStatusCode(), ex.getMessage());
                    } else {
                        response.setStatusCode(500);
                        response.setStatusMessage("HTTP 500 Internal Server Error");
                        bean = new ErrorBean(500, "Internal Server Error");
                        logger.error("Internal error catched", result);
                    }
                    response.end(Json.encode(bean));
                }
            });
        router = builder.build();
    }

    /**
     * Start the server on a specific port
     * @param port The port to start the server on
     * @param handler The handler to listen on the start
     */
    public void start(int port, Handler<AsyncResult<HttpServer>> handler) {
        init();
        logger.info("Starting REST HTTP server on port {}", port);
        httpServer = vertx.createHttpServer()
                .requestHandler(router)
                .listen(port, handler);
    }

    public Router router() {
        init();
        return router;
    }

    @Override
    public void close(Handler<AsyncResult<Void>> handler) {
        httpServer.close(handler);
    }

    public RestHTTPServer setServices(Object...services) {
        this.services = services;
        return this;
    }

    public Object[] getServices() {
        return services;
    }

    public RestHTTPServer setProviders(ContextProvider<?>...providers) {
        this.providers = providers;
        return this;
    }

    public ContextProvider<?>[] getProviders() {
        return providers;
    }

    public RestHTTPServer setInjectWith(InjectionProvider injectWith) {
        this.injectWith = injectWith;
        return this;
    }

    public InjectionProvider getInjectWith() {
        return injectWith;
    }

    public RestHTTPServer setAuthenticationProvider(AuthProvider provider) {
        this.authenticationProvider = provider;
        return this;
    }

    public AuthProvider getAuthenticationProvider() {
        return authenticationProvider;
    }

    public RestHTTPServer setValidateWith(Validator validateWith) {
        this.validateWith = validateWith;
        return this;
    }

    public Validator getValidateWith() {
        return validateWith;
    }

    public RestHTTPServer setCorsConfig(CorsConfig corsConfig) {
        this.corsConfig = corsConfig;
        return this;
    }

    public CorsConfig getCorsConfig() {
        return corsConfig;
    }

    /**
     * Basic configuration for Cors
     */
    public static class CorsConfig {
        private String allowedOriginPattern;
        private boolean allowCredentials;
        private int maxAge;
        private Set<String> allowedHeaders;
        private HttpMethod[] httpMethods;

        public String getAllowedOriginPattern() {
            return allowedOriginPattern;
        }

        public CorsConfig setAllowedOriginPattern(String allowedOriginPattern) {
            this.allowedOriginPattern = allowedOriginPattern;
            return this;
        }

        public boolean isAllowCredentials() {
            return allowCredentials;
        }

        public CorsConfig setAllowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
            return this;
        }

        public int getMaxAge() {
            return maxAge;
        }

        public CorsConfig setMaxAge(int maxAge) {
            this.maxAge = maxAge;
            return this;
        }

        public Set<String> getAllowedHeaders() {
            return allowedHeaders;
        }

        public CorsConfig setAllowedHeaders(Set<String> allowedHeaders) {
            this.allowedHeaders = allowedHeaders;
            return this;
        }

        public HttpMethod[] getHttpMethods() {
            return httpMethods;
        }

        public CorsConfig setHttpMethods(HttpMethod[] httpMethods) {
            this.httpMethods = httpMethods;
            return this;
        }
    }
}

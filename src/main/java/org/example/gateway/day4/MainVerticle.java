package org.example.gateway.day4;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import org.example.gateway.day4.config.ConfigLoader;
import org.example.gateway.day4.config.GatewayConfig;
import org.example.gateway.day4.config.RouteConfig;
import org.example.gateway.day4.config.UpstreamConfig;
import org.example.gateway.day4.filter.FilterRegistry;
import org.example.gateway.day4.filter.Filters;
import org.example.gateway.day4.proxy.ProxyHandlerFactory;
import org.example.gateway.day4.proxy.UpstreamGroup;
import org.example.gateway.day4.reload.ConfigWatcher;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Day 4 실습: day3의 프록시 위에 무중단 hot-reload를 얹는다.
 * config/day4/routes.json, upstreams.json이 바뀌면 재시작 없이 라우팅이 즉시 갱신된다.
 *
 * 핵심 안전장치: 파일 변경 감지는 별도 스레드(ConfigWatcher, WatchService)에서 일어나지만,
 * 실제로 activeRouter를 다시 만들고 교체하는 작업은 반드시 vertx.runOnContext(...)로
 * 이 Verticle의 이벤트 루프 스레드 위에서 실행한다. 그러면 activeRouter를 "쓰는" 곳(reload)과
 * "읽는" 곳(요청 처리)이 항상 같은 스레드가 되어, volatile이나 락 없이도 안전하다.
 */
public class MainVerticle extends AbstractVerticle {

    private static final String CONFIG_DIR = "config/day4";
    private static final String ROUTES_CONFIG_PATH = CONFIG_DIR + "/routes.json";
    private static final String UPSTREAMS_CONFIG_PATH = CONFIG_DIR + "/upstreams.json";

    // day3와 다른 점: registry/proxyFactory가 start() 안의 지역 변수가 아니라 인스턴스 필드다.
    // reload()가 여러 번(파일 변경마다) 호출되면서 buildRouter()도 반복 호출되는데, 그때마다
    // registry/proxyFactory까지 새로 만들면 HttpClient 커넥션 풀이 매번 새로 생겨 누수되므로
    // "설정과 무관하게 한 번만 만들 것들"은 필드로 끌어올려 재사용한다.
    private final FilterRegistry registry = new FilterRegistry()
        .register("logging", Filters.logging())
        .register("auth", Filters.auth());

    private ProxyHandlerFactory proxyFactory;

    // day3와 다른 점: day3는 buildRouter()의 결과를 startServer() 지역 변수로만 들고 있다가
    // HttpServer에 딱 한 번 requestHandler로 등록하고 끝이었다. day4는 이 Router를 통째로
    // 갈아끼워야 하므로 필드로 승격시켰다.
    // 이벤트 루프 스레드에서만 읽고 쓰인다 (reload도 runOnContext로 이 스레드에서 실행되므로
    // volatile이 필요 없다).
    private Router activeRouter;

    @Override
    public void start(Promise<Void> startPromise) {
        proxyFactory = new ProxyHandlerFactory(vertx);

        // day3와 다른 점: day3는 ConfigLoader.load(...).onSuccess(config -> startServer(...))로
        // "설정 로드 -> 라우터 조립 -> 서버 기동"이 한 번 흐르고 끝나는 일회성 파이프라인이었다.
        // day4는 그 "설정 로드 -> 라우터 조립" 부분을 reload()라는 재사용 가능한 메서드로 분리해서,
        // 최초 기동 때도 쓰고 이후 파일이 바뀔 때도 똑같이 재사용한다.
        reload()
            .compose(v -> vertx.createHttpServer()
                // day3와 다른 점: day3는 requestHandler(router)로 특정 Router 인스턴스를
                // 고정해서 등록했다. day4는 router 인스턴스 자체를 나중에 통째로 바꿔야 하므로,
                // 고정 인스턴스 대신 "매 요청마다 그 시점의 activeRouter를 찾아가는" 람다를 등록한다.
                // 이렇게 간접(indirection) 한 단계를 둬야 재시작 없는 교체가 가능해진다.
                .requestHandler(req -> activeRouter.handle(req))
                .listen(8080))
            .onSuccess(server -> {
                System.out.println("Gateway listening on port " + server.actualPort());
                // day3에는 없던 부분: 파일 감시를 시작해서, 이후 설정 변경을 계속 감지한다.
                startWatcher();
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    // day3에는 없던 메서드: day3의 startServer() 앞부분(설정 로드 + 라우터 조립 + 로그 출력)에
    // 해당하지만, "결과를 activeRouter 필드에 반영"하고 "여러 번 재호출 가능"하다는 점이 다르다.
    // 최초 기동 시 한 번, 이후 파일 변경 감지 시마다 한 번씩, 총 여러 번 호출된다.
    private Future<Void> reload() {
        return ConfigLoader.load(vertx, ROUTES_CONFIG_PATH, UPSTREAMS_CONFIG_PATH)
            .map(config -> {
                activeRouter = buildRouter(config);
                System.out.println("[reload] " + config.routes().size() + " routes loaded from " + ROUTES_CONFIG_PATH);
                for (RouteConfig rc : config.routes()) {
                    System.out.println("  " + rc.path() + " -> filters=" + rc.filters()
                        + " upstreamGroup=" + rc.upstreamGroup());
                }
                return (Void) null;
            });
    }

    // day3에는 없던 메서드: 파일 변경을 감지해서 reload()를 트리거하는 부분 전체가 day4의
    // 신규 기능이다.
    private void startWatcher() {
        Path dir = Path.of(CONFIG_DIR);
        ConfigWatcher watcher = new ConfigWatcher(dir, Set.of("routes.json", "upstreams.json"),
            // ConfigWatcher는 별도 스레드에서 콜백을 부르므로, 실제 reload는
            // runOnContext로 이 Verticle의 이벤트 루프 스레드로 되돌려서 실행한다.
            () -> vertx.runOnContext(v -> reload()
                .onFailure(err -> System.err.println("[reload] failed: " + err.getMessage()))));
        watcher.start();
    }

    // day3와 다른 점: day3의 buildRouter(config, registry, proxyFactory)는 세 값을 전부
    // 파라미터로 받았다. day4는 registry/proxyFactory가 인스턴스 필드가 됐으니 그 두 개는
    // 파라미터에서 빠지고 config 하나만 받는다. 이 메서드 자체의 로직(라우트마다 필터 체인 +
    // 프록시 핸들러 조립)은 day3와 완전히 동일하다 — reload() 때마다 매번 새로 호출된다는
    // 점만 다르다.
    private Router buildRouter(GatewayConfig config) {
        Router router = Router.router(vertx);
        UpstreamConfig upstreamConfig = config.upstreams();
        Map<String, UpstreamGroup> groups = new HashMap<>();

        for (RouteConfig rc : config.routes()) {
            Route route = router.route(rc.path());
            for (String filterName : rc.filters()) {
                route.handler(registry.get(filterName));
            }
            if (rc.upstreamGroup() != null) {
                UpstreamGroup group = groups.computeIfAbsent(rc.upstreamGroup(),
                    name -> new UpstreamGroup(upstreamConfig.group(name)));
                route.handler(proxyFactory.forGroup(group));
            }
        }
        return router;
    }
}

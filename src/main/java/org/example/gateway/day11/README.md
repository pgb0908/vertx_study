# day11 — Vert.x Future/Promise 합성 패턴

day10 작업 중 `VertxUpstreamClient`의 `.onFailure().onSuccess()` 체이닝 문법이
헷갈려서 만든 학습 섹션. 기초 개념(왜 Future가 필요한가)부터 응용(실제 파이프라인
조립)까지 순서대로 실행하며 익힌다. 서버가 아니라 콘솔 출력으로 개념을 확인하는
용도라, 다른 day와 달리 `TESTING.md`(curl 검증) 대신 이 `README.md`로 설명한다.

## 실행

```bash
./gradlew runDay11
```

레슨 0부터 8까지 순서대로 콘솔에 출력된다. `Main.java`가 각 레슨을 `.compose()`로
이어붙여 실행하는데, 이 `Main.java` 자체가 이미 Lesson 5(compose)의 실전 예제다.

## 레슨 목록

| 레슨 | 파일 | 핵심 질문 |
|---|---|---|
| 0 | `L0_LambdaTypeInference` | `id -> { ... }`에서 `id`의 타입은 어디서 정해지나? |
| 1 | `L1_CallbackStyle` | Future가 왜 필요한가 — 콜백만으로 짜면 뭐가 불편한가? |
| 2 | `L2_FutureAndPromise` | `Promise`와 `Future`는 각각 무엇의 손잡이인가? |
| 3 | `L3_OnSuccessOnFailure` | `.onFailure().onSuccess()`가 왜 체이닝되나? |
| 4 | `L4_MapFunctor` | `.map()`은 정확히 무슨 규칙으로 동작하나 (Functor)? |
| 5 | `L5_ComposeMonad` | `.map()`과 `.compose()`는 뭐가 다른가 (Monad)? |
| 6 | `L6_OnCompleteAndRecover` | 체인 중간 실패는 어떻게 전파/복구되나? |
| 7 | `L7_CompletableFutureEquivalents` | day10 domain/engine의 `CompletableFuture`와 어떻게 대응되나? |
| 8 | `L8_AppliedExercise` | 실제 `VertxUpstreamClient`와 같은 모양을 처음부터 조립하면? |

## 핵심 개념 요약

질문: "compose, map 이런 패턴 용법들을 뭐라고 불러?"에 대한 답을 코드로 확인하는
것이 이 day의 목표다.

| 이름 | 무엇을 가리키나 | 대응 예시 |
|---|---|---|
| Functor / `map` | 상자 안 값을 변환 (중첩 안 생김) | `Optional.map`, `Stream.map`, `Future.map`, `CompletableFuture.thenApply` |
| Monad / `compose`(`flatMap`) | 상자 안 값으로 새 상자를 만들고 평탄화 | `Optional.flatMap`, `Future.compose`, `CompletableFuture.thenCompose` |
| Promise/Future 패턴 | 비동기 결과를 나타내는 "상자" 자체 | `Promise`/`Future`, JS의 `Promise`, JDK의 `CompletableFuture` |
| Continuation-Passing Style | "그다음에 할 일"을 콜백으로 넘기는 전체 스타일 | Lesson 1의 콜백 피라미드 |
| Fluent API / Method Chaining | 점(`.`)으로 계속 이어 쓰는 문법 디자인 | `.onFailure().onSuccess()`가 가능한 이유 |

## 람다 타입 추론 (Lesson 0 요약)

람다 자체는 타입 정보가 없다. 컴파일러는:

1. 람다가 어떤 메서드의 인자로 들어가는지 찾고
2. 그 파라미터의 선언 타입(함수형 인터페이스)을 보고
3. 그 인터페이스의 유일한 추상 메서드 시그니처에서 파라미터 타입을 위치대로 읽는다

`vertx.setTimer(delay, id -> ...)`의 `id`가 `Long`인 이유는 `setTimer`가
`Handler<Long>`을 받는다고 선언돼 있어서다 — 변수 이름(`id`)은 아무 의미 없는
자리표시자일 뿐이다.

## 다음에 볼 것

이 레슨들을 다 보고 나면 day10의 다음 파일들이 훨씬 쉽게 읽힌다:

- `day10/runtime/vertx/VertxUpstreamClient.java` — Lesson 8과 거의 같은 모양
- `day10/Main.java` — `vertx.deployVerticle(...).compose(...)`가 Lesson 5와 동일
- `day10/runtime/vertx/VertxResponseWriter.java` — Vert.x `Future`와
  `CompletableFuture`가 실제로 만나는 지점(Lesson 7)

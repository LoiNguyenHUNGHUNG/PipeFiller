# Bandwidth-Timeout Inference

This project implements a static analysis tool that infers the minimum global bandwidth required to prevent timeout violations in a restricted Kotlin DSL for download concurrency. 

The tool uses a compositional summary **QK = (q, k)**.

* **q**: maximum number of downloads that may run **concurrently**
* **k**: the **largest per-download required rate** among downloads that may occur
  where `rate = size / timeout`

For a whole program rooted at `main()`, we report a **peak bandwidth bound**:

```
B = k x min(q, K)
```

where **K** is a global concurrency cap (configurable; default e.g. 64).

---

## 1) Primitive Downloads

A “download” is modeled by a function annotated with:

```kotlin
@DownloadSpec(size = ..., timeout = ..., prio = ...)
fun dlX() { ... }
```

Its QK summary is:

* `q = 1`
* `k = size / timeout`

> Note: `prio` is currently recorded but not yet used unless/when scheduler abstractions are added.

---

## 2) What the DSL Allows

The analysis supports only a small set of constructs. Anything else is currently treated conservatively as **(0,0)** (and may later become an explicit “DSL violation”).

### A) Sequential composition

Plain Kotlin sequencing (blocks / statements in order):

```kotlin
fun main() {
  dlA()
  dlB()
}
```

Sequential composition does **not** add concurrency; it takes maxima:

* `seq((q1,k1),(q2,k2)) = (max(q1,q2), max(k1,k2))`

---

### B) `if / else`

Branches are mutually exclusive; condition is evaluated first:

```kotlin
if (cond) dlA() else dlB()
```

Rule (safe over-approximation):

* `q = max(q_cond, q_then, q_else)`
* `k = max(k_cond, k_then, k_else)`

---

### C) Interprocedural calls (no recursion)

If a called function is not annotated with `@DownloadSpec`, we analyze its **body** and use that QK.

```kotlin
fun dlB() { dlA() } // dlB inherits dlA’s QK

fun main() { dlB() }
```

Assumption: **no recursion** (direct or mutual). Each function is analyzed once, on-demand.

---

### D) `coroutineScope { launch { ... } ... }` (restricted)

We treat `coroutineScope` as the DSL’s **parallel composition** operator, with a strict restriction:

> Inside `coroutineScope { ... }`, the body must be **a bunch of `launch { ... }` calls only**.
> No other statements between launches (no `foo()`, no assignments, no `if`, etc.).

Allowed example:

```kotlin
coroutineScope {
  launch { dlA() }
  launch { dlB() }
  launch { dlC() }
}
```

Rule (default conservative scheduler: all may overlap):

* Each `launch { e_i }` contributes QK of `e_i`
* `k = max_i k_i`
* `q = sum_i q_i`

So `coroutineScope` behaves like the calculus `seq(...)` node.

---

## 3) What the DSL Forbids (for now)

These are **out of scope** initially (either treated as (0,0) or later flagged as “DSL violation”):

* Loops: `for`, `while`, `repeat`, collection ops that spawn jobs, etc.
* Unstructured concurrency: `GlobalScope.launch`, launching from callbacks, channels/flows, etc.
* `async/await` (unless explicitly added later)
* `coroutineScope` bodies that mix launches with other statements, e.g.:

```kotlin
coroutineScope {
  launch { dlA() }
  foo()              // NOT allowed in DSL (for now)
  launch { dlB() }
}
```

---

## 4) Output

The Detekt rule analyzes `main()` and reports:

```
BandwidthSummary q=<q> k=<k> K=<Kcap> B=<B>
```

Interpretation: Under the DSL assumptions and conservative scheduling, provisioning bandwidth `B` is sufficient to avoid rate-based “bad timeouts” implied by `@DownloadSpec`.

---

## 5) Example

```kotlin
@DownloadSpec(size = 10.0, timeout = 2.0, prio = Prio.L) // rate=5
fun dlA() {}

@DownloadSpec(size = 8.0, timeout = 2.0, prio = Prio.L)  // rate=4
fun dlB() {}

fun main() = runBlocking {
  coroutineScope {
    launch { dlA() }
    launch { dlB() }
  }
}
```

* dlA: `(1, 5)`
* dlB: `(1, 4)`
* coroutineScope: `q = 1+1 = 2`, `k = max(5,4)=5`
* Bandwidth bound: `B = 5 * min(2,K) = 10` (if `K >= 2`)

## TODO: Scheduler-Parametric Analysis

Right now the analysis assumes a *maximally concurrent*, priority-agnostic scheduler:

- All downloads that can overlap are assumed to overlap.
- The global cap `K` is the only limit on concurrency.
- The `prio` field in `@DownloadSpec` is parsed but not used.

This is sound but often overly conservative.

A major next step is to make the analysis **scheduler-parametric**, so that users can describe how concurrency is actually constrained in their system, and PipeFiller will infer bandwidth requirements *relative to that scheduler*.

At a high level, we want users to be able to:

- Define a **runtime scheduler** that their program uses.
- Provide a corresponding **scheduler abstraction** that the inference tool uses to bound concurrency.

### 1. Scheduler abstraction interface (analysis side)

We plan to introduce a scheduler abstraction interface in the analysis API that mirrors the paper’s model of schedulers.

Conceptually, a scheduler abstraction:

- Takes per-child concurrency summaries (e.g., internal `k_i` values),
- Takes per-child priorities,
- Returns an upper bound on how many downloads the entire parallel region may realize concurrently.

In the paper, this abstraction is an arbitrary function with a type of `Seq[Nat] -> Seq[P] -> Nat`

For the implementation, we likely want a restricted and safe interface rather than executing arbitrary user code inside the analyzer.

Open design questions include:

- Should users attach a scheduler via an annotation before each `coroutineScope`?
- Should we provide a wrapper such as `runCoroutineWithScheduler(...)`?

### 2. Runtime scheduler framework (execution side)

In principle, we would like a framework for installing runtime schedulers that
correspond to the abstractions used in the analysis. This would allow users to:

- Implement a concrete runtime scheduler,
- Provide a matching scheduler abstraction to the inference tool,
- And obtain bandwidth guarantees that are sound relative to that scheduler.

From an engineering perspective, this is largely a Kotlin problem. The language
does not make it straightforward to override the default coroutine scheduler,
so we would likely need to:

- Provide a wrapper around `coroutineScope { ... }`,
- Control how `launch { ... }` blocks are scheduled within that wrapper,
- Ensure that the runtime scheduler never realizes more concurrency than the
  abstraction used in the analysis allows.

However, this is not immediately necessary for the current stage of the project.

At the moment, we do **not** rely on executing programs to evaluate the analysis.
Instead, we manually compute the precise peak bandwidth demand of DSL programs
and use that as ground truth to evaluate the inference tool. As a result,
a concrete runtime scheduler implementation is not required for validation.

Therefore, runtime scheduler integration is best viewed as **future work**.

# XX科技研发团队编码规范手册

> **版本**: v2.0 | **生效日期**: 2026-01-01 | **适用范围**: XX科技全部Java后端研发项目
> **编制委员会**: XX科技架构组 & 技术标准委员会

---

## 目录

1. [命名规范](#1-命名规范)
2. [代码格式](#2-代码格式)
3. [异常处理](#3-异常处理)
4. [并发编程](#4-并发编程)
5. [数据库规约](#5-数据库规约)
6. [安全规约](#6-安全规约)
7. [日志规约](#7-日志规约)
8. [工程结构规约](#8-工程结构规约)

---

## 1. 命名规范

命名是代码可读性的基石。XX科技采用"语义驱动命名法"（Semantic-Driven Naming），要求每个标识符的名字必须能够独立传达其职责和意图，不依赖上下文即可理解。

### 1.1 【强制】包名全小写，使用反写域名前缀 + 模块功能路径

包名必须以公司域名反写开头（`com.xxtech`），后续按功能模块分层。禁止使用复数形式、缩写无规律、或包含下划线/美元符号。

**正例**：
```java
package com.xxtech.user.service;
package com.xxtech.order.repository;
package com.xxtech.payment.gateway.alipay;
```

**反例**：
```java
package com.XXTech.User.Service;           // 错误：大小写混用
package com.xxtech.user_services;            // 错误：使用下划线
package com.xxtech.usrSvc;                   // 错误：无意义缩写
```

### 1.2 【强制】类名使用 UpperCamelCase，接口与实现类的命名契约

类名必须采用大驼峰命名。接口命名优先使用名词或形容词（`UserService`、`Runnable`）；实现类在接口名后加 `Impl` 后缀，或在描述性名称中体现实现技术。

**正例**：
```java
// 接口
public interface UserRepository { ... }
public interface Cacheable<T> { ... }

// 实现
public class JdbcUserRepository implements UserRepository { ... }
public class RedisCacheable<V> implements Cacheable<V> { ... }
public class DefaultOrderProcessor implements OrderProcessor { ... }
```

**反例**：
```java
public interface userService { ... }          // 错误：小驼峰
public class UserServiceImpl implements IUserService { ... }  // 错误：接口不应带I前缀（非C#惯例）
public class UserServiceImplementation implements UserService { ... } // 冗余
```

### 1.3 【强制】方法名使用 lowerCamelCase，动词或动词短语开头

方法名以小驼峰呈现，首词为动词或动词短语，明确表达行为语义。getter/setter/布尔判断遵循JavaBean规范。

**正例**：
```java
public User createUser(CreateUserRequest request);
public void updateProfile(Long userId, ProfileDTO dto);
public boolean isActive();
public List<Order> getPendingOrders();
public void processPayment(PaymentCommand command);
```

**反例**：
```java
public User Create_User(CreateUserRequest r); // 错误：大小写+下划线
public List<Order> getorders();               // 错误：拼写错误
public boolean active();                      // 错误：缺少is前缀（布尔方法）
public User userData();                       // 错误：名词开头，意图不明
```

### 1.4 【强制】常量命名 UPPER_SNAKE_CASE，枚举同样遵循

常量（static final）必须全大写下划线分隔。枚举类名UpperCamelCase，枚举值UPPER_SNAKE_CASE。魔法值必须提取为常量。

**正例**：
```java
public class OrderConstants {
    public static final int MAX_ORDER_ITEMS = 99;
    public static final String ORDER_PREFIX = "ORD";
    public static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(30);
}

public enum OrderStatus {
    PENDING("待支付"),
    PAID("已支付"),
    SHIPPED("已发货"),
    COMPLETED("已完成"),
    CANCELLED("已取消");

    private final String description;
}
```

**反例**：
```java
// 魔法值散落在代码各处
if (order.getStatus() == 3) { ... }              // 错误：3是什么状态？
if ("SUCCESS".equals(result.getCode())) { ... }   // 错误：字符串字面量
public static final int maxitems = 99;             // 错误：命名不规范
```

### 1.5 【推荐】变量名语义化，避免单字母变量（除循环计数器）

局部变量、参数、成员变量均应使用有意义的名称。循环计数器可用 `i/j/k`，lambda参数在类型明确时可简写，但复杂逻辑中禁止。

**正例**：
```java
// 清晰的变量命名
public void calculateDiscount(Order order, Customer customer) {
    BigDecimal originalAmount = order.getTotalAmount();
    BigDecimal discountRate = customer.getMemberLevel().getDiscountRate();
    BigDecimal finalAmount = originalAmount.multiply(BigDecimal.ONE.subtract(discountRate));
    order.setFinalAmount(finalAmount.setScale(2, RoundingMode.HALF_UP));
}

// Lambda 简写在类型明确时允许
users.stream()
     .filter(u -> u.isActive())
     .map(u -> u.getName())
     .collect(Collectors.toList());
```

**反例**：
```java
// 意图模糊的变量名
public void calc(Order o, Customer c) {
    BigDecimal a = o.getAmt();
    BigDecimal d = c.getMl().getDr();
    BigDecimal f = a.multiply(BigDecimal.ONE.subtract(d));
    o.setFa(f.setScale(2, RoundingMode.HALF_UP));
}

// 复杂Lambda中使用单字母参数
list.stream()
    .map(x -> x.getFieldA() * x.getFieldB() + x.getFieldC())
    .filter(x -> x > 100)
    .collect(Collectors.toList());
```

### 1.6 【参考】DTO/VO/Entity 的字段映射保持一致性

当数据库字段为 `snake_case` 时，Java实体类字段用 `camelCase`，通过MyBatis配置自动映射。但跨层传输对象（DTO→VO）的字段名应保持一致或使用明确的转换策略。

---

## 2. 代码格式

统一的代码格式是团队协作效率的基础保障。XX科技强制使用项目级 formatter 配置（基于 Google Java Style 定制）。

### 2.1 【强制】大括号不换行（Egyptian风格），if/else/for/while/do 必须使用大括号

即使只有一条语句，控制结构也必须使用大括号。左大括号前不换行。这减少了后续添加语句时忘记加大括号的缺陷风险。

**正例**：
```java
if (order.getAmount().compareTo(BigDecimal.ZERO) > 0) {
    processPayment(order);
} else {
    log.warn("订单金额为零，跳过支付: orderId={}", order.getId());
}

for (OrderItem item : order.getItems()) {
    validateItem(item);
    calculateTax(item);
}
```

**反例**：
```java
// 缺少大括号 - 危险模式
if (order.isValid())
    submit(order);        // 只有这一句时看似正常

// 后续有人加了第二行，但逻辑已经改变！
if (order.isValid())
    submit(order);
    sendNotification(order);  // 这行无论order是否有效都会执行！

// 大括号换行风格（不符合本规范）
if (order.getAmount().compareTo(BigDecimal.ZERO) > 0)
{
    processPayment(order);
}
```

### 2.2 【强制】每行最大字符数 120，超长语句合理折行

超过120字符的行必须在操作符后、逗号后折行，缩进对齐到上一行表达式的起始位置加4空格或挂缩进8空格。

**正例**：
```java
// 方法调用链折行
List<User> activeUsers = userRepository.findByStatusAndCreatedAfter(
        UserStatus.ACTIVE,
        LocalDateTime.now().minusDays(30)
);

// 三元表达式折行
String displayName = user.getNickname() != null && !user.getNickname().isBlank()
        ? user.getNickname()
        : user.getUsername();

// 流式API长链折行
Map<String, Long> categoryCount = orders.stream()
        .filter(o -> o.getStatus() == OrderStatus.COMPLETED)
        .collect(Collectors.groupingBy(
                Order::getCategory,
                Collectors.counting()
        ));
```

### 2.3 【强制】方法长度不超过 80 行，单个方法只做一件事

一个方法应该只完成一个明确的任务。如果方法超过80行，应当考虑拆分。复杂业务逻辑建议使用策略模式或模板方法模式进行拆分。

**正例**：
```java
// 拆分后的清晰结构
public OrderResult placeOrder(PlaceOrderCommand command) {
    // 1. 参数校验
    validateCommand(command);

    // 2. 库存检查
    InventoryCheckResult inventory = checkInventory(command);

    // 3. 创建订单
    Order order = createOrder(command, inventory);

    // 4. 扣减库存
    deductInventory(inventory);

    // 5. 异步触发后续流程
    publishOrderCreatedEvent(order);

    return OrderResult.success(order.getId());
}
```

**反例**：
```java
// 上帝方法 - 超过200行，混合了校验、库存、优惠、风控、创建等所有逻辑
public OrderResult placeOrder(PlaceOrderCommand cmd) {
    if (cmd == null) throw new IllegalArgumentException("cmd is null");
    if (cmd.getUserId() == null) throw new IllegalArgumentException("userId is null");
    if (cmd.getItems() == null || cmd.getItems().isEmpty()) throw new ...
    // ... 50行校验 ...

    for (OrderItem item : cmd.getItems()) {
        Product p = productRepo.findById(item.getProductId());
        if (p == null) { ... }
        if (p.getStock() < item.getQuantity()) { ... }
        // ... 40行库存检查 ...
    }

    // ... 50行优惠券计算 ...
    // ... 30行风控检查 ...
    // ... 40行订单创建 ...
    // ... 20行事件发布 ...
}
```

### 2.4 【推荐】顺序：常量 → 成员变量 → 构造函数 → 方法（私有排最后）

类成员按照以下顺序排列，每组之间用一个空行分隔：

1. `static final` 常量
2. `static` 成员变量
3. `final` 实例变量
4. 普通实例变量
5. 构造函数 / 工厂方法
6. 公共方法
7. 受保护方法
8. 私有方法（辅助方法紧随其调用者）

### 2.5 【推荐】使用 Optional 替代 null 返回值，但禁止用作方法参数

`Optional` 用于表达"可能不存在"的返回值语义，使 API 契约更明确。但 `Optional` 不应作为字段类型或方法参数——它增加了序列化开销和方法调用的复杂性。

**正例**：
```java
// 正确：Optional 作为返回值
public Optional<User> findById(Long id) {
    return Optional.ofNullable(userMapper.selectById(id));
}

// 正确：调用方优雅处理
public String getUsername(Long userId) {
    return userRepository.findById(userId)
            .map(User::getUsername)
            .orElse("未知用户");
}
```

**反例**：
```java
// 错误：Optional 作为方法参数
public void updateUser(@NotNull Optional<String> nickname,
                       @NotNull Optional<String> avatar) { ... }

// 错误：Optional 作为字段
public class User {
    private Optional<String> nickname;  // 序列化问题！
}

// 错误：直接get()不做检查
User user = userRepository.findById(id).get();  // NoSuchElementException风险
```

### 2.6 【参考】Lombok 使用指南

推荐使用 `@Data`（仅用于 DTO/VO）、`@Builder`、`@Slf4j`、`@RequiredArgsConstructor`。禁止在 Entity 类上使用 `@Data`（应使用 `@Getter/@Setter` 精确控制）。禁止 `@Synchronized`（应使用并发工具类）。

---

## 3. 异常处理

异常处理是系统健壮性的核心防线。XX科技采用"分层异常 + 全局捕获"的策略，确保异常信息既有利于排查又不泄露内部细节。

### 3.1 【强制】禁止吞掉（swallow）异常，至少记录日志

空的 catch 块是最危险的代码模式之一。即使确定某个异常可以忽略，也必须注释说明原因。

**正例**：
```java
try {
    int retryCount = 0;
    while (retryCount < MAX_RETRY) {
        try {
            return callExternalService(param);
        } catch (ConnectException e) {
            retryCount++;
            if (retryCount >= MAX_RETRY) {
                throw new ServiceUnavailableException("外部服务不可用，已重试" + MAX_RETRY + "次", e);
            }
            Thread.sleep(RETRY_INTERVAL_MS);
        }
    }
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // 恢复中断标志！
    throw new OperationInterruptedException("操作被中断", e);
}
```

**反例**：
```java
// 致命的异常吞没
try {
    saveToDatabase(record);
} catch (Exception e) {
    // TODO: fix later      ← 最常见的"墓碑式"注释
}

// 更隐蔽的吞没方式
try {
    riskyOperation();
} catch (Exception ignored) {
    // ignored ← 用变量名来掩盖问题
}
```

### 3.2 【强制】自定义业务异常继承统一基类，携带错误码

所有业务异常必须继承 `BizException`（或项目定义的业务异常基类），包含 `errorCode` 和可读的 `message`。全局异常处理器负责将异常转换为标准化的 API 响应。

**正例**：
```java
// 业务异常基类
public class BizException extends RuntimeException {
    private final String errorCode;

    public BizException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
    // getter...
}

// 具体业务异常
public class InsufficientBalanceException extends BizException {
    public InsufficientBalanceException(BigDecimal requested, BigDecimal available) {
        super("BALANCE_001",
              "余额不足: 需要 " + requested + ", 可用 " + available);
    }
}

// 全局异常处理器
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(e.getErrorCode(), e.getMessage()));
    }
}
```

**反例**：
```java
// 直接抛出RuntimeException或Exception
throw new RuntimeException("余额不足");       // 无错误码，难以追踪
throw new Exception("操作失败");                 // 太宽泛

// 在Controller里直接catch并返回
@PostMapping("/order")
public Result createOrder(@RequestBody OrderDTO dto) {
    try {
        return Result.ok(orderService.create(dto));
    } catch (Exception e) {                     // 所有异常一把抓
        return Result.fail(e.getMessage());      // 可能暴露堆栈/SQL给前端
    }
}
```

### 3.3 【强制】finally 块中不使用 return，不抛出新异常

`finally` 块中的 `return` 会覆盖 `try` 或 `catch` 块中的原始返回值和异常，导致难以调试的逻辑错误。`finally` 中抛出的新异常会掩盖原始异常。

**正例**：
```java
InputStream is = null;
try {
    is = new FileInputStream(file);
    return processStream(is);       // try中的return
} catch (IOException e) {
    throw new DataProcessingException("文件处理失败", e);
} finally {
    if (is != null) {
        try {
            is.close();             // 只做资源清理
        } catch (IOException closeEx) {
            log.warn("关闭流失败", closeEx);  // 记录但不影响主流程
        }
    }
}
```

**反例**：
```java
// finally中的return会"吃掉"try/catch中的任何异常
try {
    throw new IllegalStateException("严重错误");
} finally {
    return;   // 上面的IllegalStateException被静默丢弃了！调用方得到的是null/默认值
}

// finally中抛出新异常
try {
    riskyOperation();
} catch (OriginalException e) {
    throw new WrappedException("包装异常", e);
} finally {
    cleanupResource();   // 如果这个方法抛异常，OriginalException就丢失了
}
```

### 3.4 【推荐】使用 try-with-resources 管理所有实现了 AutoCloseable 的资源

从 Java 7 起，`try-with-resources` 是管理资源的标准方式。它保证资源按声明顺序逆序关闭，即使在返回或异常情况下也能正确执行。

**正例**：
```java
// try-with-resources - 简洁安全
public String readFileContent(Path path) throws IOException {
    StringBuilder content = new StringBuilder();
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line).append("\n");
        }
    }  // reader 自动关闭
    return content.toString();
}

// 多个资源
try (Connection conn = dataSource.getConnection();
     PreparedStatement ps = conn.prepareStatement(SQL);
     ResultSet rs = ps.executeQuery()) {

    while (rs.next()) { ... }
}  // rs -> ps -> conn 逆序关闭
```

### 3.5 【推荐】区分受检异常与非受检异常的使用场景

受检异常（checked exception）用于**可恢复的外部依赖故障**（网络IO、文件系统、数据库连接）。非受检异常用于**编程错误或不可恢复的系统故障**（空指针、非法参数、状态不一致）。

| 场景 | 异常类型 | 示例 |
|------|---------|------|
| 外部HTTP调用失败 | 受检 | `IOException`, `TimeoutException` |
| 数据库连接断开 | 受检 | `SQLException` |
| 参数校验失败 | 非受检 | `IllegalArgumentException` |
| 对象状态非法 | 非受检 | `IllegalStateException` |
| 业务规则违反 | 非受检 | `BizException`(自定义) |

### 3.6 【参考】异常链保留完整因果链条

捕获一个异常并抛出新异常时，始终将原异常作为 cause 传递。不要只提取 message 字符串重新构造——那样会丢失堆栈跟踪。

---

## 4. 并发编程

并发编程是后端开发中最容易出错且最难调试的领域之一。XX科技在此领域采取保守策略：优先使用高级并发工具，谨慎使用底层同步原语。

### 4.1 【强制】共享可变状态必须通过线程安全类或同步机制保护

任何可能被多个线程同时访问的可变状态，都必须使用线程安全的容器、原子类或显式锁保护。`HashMap`、`ArrayList`、`SimpleDateFormat` 等非线程安全类严禁在多线程间共享。

**正例**：
```java
// 使用线程安全的集合
private final ConcurrentHashMap<String, UserData> cache = new ConcurrentHashMap<>();
private final CopyOnWriteArrayList<EventListener> listeners = new CopyOnWriteArrayList<>();

// 使用原子类替代手动同步
private final AtomicLong counter = new AtomicLong(0);
private final AtomicReference<Config> currentConfig = new AtomicReference<>();

public long nextId() {
    return counter.incrementAndGet();
}

public void updateConfig(Config newConfig) {
    currentConfig.set(newConfig);
}
```

**反例**：
```java
// 经典的并发陷阱
private Map<String, String> dataMap = new HashMap<>();  // 非线程安全！

public void put(String key, String value) {
    dataMap.put(key, value);  // 多线程put可能导致HashMap进入死循环（JDK7前）或数据丢失
}

public String get(String key) {
    return dataMap.get(key);  // 可能读到半构造状态
}

// 另一个经典陷阱
private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
// SimpleDateFormat不是线程安全的！多线程调用format()/parse()会产生混乱结果
```

### 4.2 【强制】禁止在锁内执行耗时操作（IO、RPC、HTTP）

持有锁期间执行耗时操作会导致所有其他等待该锁的线程阻塞，极端情况下会造成系统雪崩。如果必须在临界区内调用外部服务，应考虑改用乐观锁或分段锁。

**正例**：
```java
// 先获取数据，释放锁后再执行IO
public OrderResult processOrder(Long orderId) {
    Order order;
    synchronized (orderLock) {
        order = orderMap.get(orderId);  // 快速获取，立即释放锁
    }
    // 锁外执行耗时操作
    InventoryInfo inventory = inventoryService.checkRemote(order.getSkuIds());
    PriceInfo price = pricingService.calculateRemote(order);

    synchronized (orderLock) {
        order.setInventory(inventory);
        order.setPrice(price);
        order.setStatus(OrderStatus.PROCESSING);
    }
    return buildResult(order);
}
```

**反例**：
```java
// 反例：锁内调用远程服务
public synchronized OrderResult processOrder(Long orderId) {
    Order order = orderMap.get(orderId);

    // 以下两个远程调用可能耗时数百毫秒到数秒
    // 在此期间所有需要processOrder锁的线程全部阻塞
    InventoryInfo inventory = inventoryService.checkRemote(order.getSkuIds());
    PriceInfo price = pricingService.calculateRemote(order);

    order.setInventory(inventory);
    order.setPrice(price);
    return buildResult(order);
}
```

### 4.3 【强制】ThreadLocal 使用后必须 remove()，防止内存泄漏

`ThreadLocal` 在线程池场景中尤其危险：线程复用导致上一次请求设置的 ThreadLocal 值污染当前请求。必须在 `finally` 块中调用 `remove()`。

**正例**：
```java
private static final ThreadLocal<UserContext> USER_CONTEXT = new ThreadLocal<>();

public void executeWithUser(Long userId, Runnable task) {
    UserContext ctx = loadContext(userId);
    USER_CONTEXT.set(ctx);
    try {
        task.run();
    } finally {
        USER_CONTEXT.remove();  // 必须！防止线程池复用时上下文泄漏
    }
}
```

**反例**：
```java
// 只set不remove - 在Tomcat线程池中这是内存泄漏+数据污染的根源
public void filter(HttpServletRequest req) {
    UserContext ctx = parseToken(req.getHeader("Authorization"));
    USER_CONTEXT.set(ctx);   // set了
    chain.doFilter(req, res); // 如果后面的代码异常，remove永远不会被执行
    // 缺少 finally { USER_CONTEXT.remove(); }
}
```

### 4.4 【推荐】使用线程池代替手动创建线程

禁止在业务代码中直接 `new Thread()` 或 `new ThreadPoolExecutor()` 且不交给容器管理。应使用 Spring 的 `@Async` + 线程池配置，或自定义线程池 Bean 并统一管理。

**正例**：
```java
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("async-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}

// 使用
@Async("taskExecutor")
public CompletableFuture<Report> generateReportAsync(Long userId) {
    // 异步执行报表生成
    return CompletableFuture.completedFuture(buildReport(userId));
}
```

### 4.5 【推荐】CompletableFuture 编程注意事项

使用 `CompletableFuture` 进行异步编排时：
- 自定义线程池，避免共用 `ForkJoinPool.commonPool()`
- 链式调用中每个阶段都要处理异常 (`exceptionally` / `handle`)
- 避免 `thenApply` 中抛出未声明的受检异常
- `get()` 必须设超时，禁止无限等待

**正例**：
```java
public CompletableFuture<OrderSummary> getOrderSummary(Long orderId) {
    // 并行获取多个依赖
    CompletableFuture<Order> orderFuture = CompletableFuture.supplyAsync(
            () -> orderRepository.findById(orderId), orderExecutor);

    CompletableFuture<List<OrderItem>> itemsFuture = orderFuture.thenCompose(
            order -> CompletableFuture.supplyAsync(
                    () -> itemRepository.findByOrderId(orderId), itemExecutor));

    CompletableFuture<User> userFuture = orderFuture.thenCompose(
            order -> CompletableFuture.supplyAsync(
                    () -> userRepository.findById(order.getUserId()), userExecutor));

    // 合并结果，带超时和异常处理
    return orderFuture.thenCombine(itemsFuture, (order, items) ->
                    new Pair<>(order, items))
            .thenCombine(userFuture, (pair, user) ->
                    buildSummary(pair.getLeft(), pair.getRight(), user))
            .exceptionally(ex -> {
                log.error("获取订单摘要失败, orderId={}", orderId, ex);
                return OrderSummary.empty(orderId);
            });
}

// 调用方设置超时
OrderSummary summary = service.getOrderSummary(orderId)
        .get(5, TimeUnit.SECONDS);
```

### 4.6 【参考】volatile 的正确使用场景

`volatile` 保证可见性和有序性，但不保证原子性。适用于：
- 状态标志位（停止信号）
- 单次发布的不可变对象引用
- 双重检查锁定（DCL）的单例模式

不适用于：计数器、累加器等需要原子复合操作的场景（应用 `AtomicLong` / `LongAdder`）。

---

## 5. 数据库规约

数据库是大多数企业应用的核心资产。不当的数据库操作是性能瓶颈的头号来源。

### 5.1 【强制】禁止 SELECT *，必须明确列出所需字段

`SELECT *` 会浪费网络带宽、内存和CPU；表结构变更时还可能导致应用层代码意外崩溃。

**正例**：
```java
// MyBatis XML - 明确指定字段
<select id="findActiveUsers" resultType="com.xxtech.domain.User">
    SELECT
        id, username, nickname, email, status, created_at
    FROM users
    WHERE status = 'ACTIVE'
    AND deleted = 0
    LIMIT #{limit}
</select>
```

**反例**：
```sql
-- 性能杀手
SELECT * FROM users WHERE status = 'ACTIVE';

-- 当表新增了一个 BLOB 字段后，上面的查询会把 BLOB 也加载到内存
```

### 5.2 【强制】超过 3 表 JOIN 必须经过架构评审

多表关联查询是数据库性能的主要杀手。超过3表的JOIN通常意味着数据模型设计不合理，或者应该在应用层进行数据组装。

**正例**：
```java
// 应用层组装 - 每个查询都走索引
public OrderDetailVO getOrderDetail(Long orderId) {
    Order order = orderMapper.selectById(orderId);           // 主表
    List<OrderItem> items = itemMapper.selectByOrderId(orderId);  // 从表
    User user = userMapper.selectById(order.getUserId());    // 用户表
    Address address = addressMapper.selectById(order.getAddressId()); // 地址表

    return OrderDetailVO.builder()
            .order(order).items(items).user(user).address(address)
            .build();
}
```

**反例**：
```sql
-- 5表JOIN - 任何一个表的数据量变大都会导致性能急剧下降
SELECT o.*, oi.*, u.*, a.*, p.*
FROM orders o
LEFT JOIN order_items oi ON o.id = oi.order_id
LEFT JOIN users u ON o.user_id = u.id
LEFT JOIN addresses a ON o.address_id = a.id
LEFT JOIN products p ON oi.product_id = p.id
WHERE o.id = #{orderId}
```

### 5.3 【强制】批量操作必须使用 Batch 方式，禁止循环单条 SQL

在循环中逐条执行 INSERT/UPDATE/DELETE 是最常见的性能反模式之一。MyBatis 的 `BatchSession` 或 JDBC Batch 可以将网络往返减少 N 倍。

**正例**：
```java
// MyBatis 批量插入
@Transactional
public void batchInsertOrders(List<Order> orders) {
    SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH);
    try {
        OrderMapper mapper = session.getMapper(OrderMapper.class);
        for (Order order : orders) {
            mapper.insert(order);
        }
        session.commit();   // 一次性提交
    } finally {
        session.close();
    }
}
```

**反例**：
```java
// 循环单条插入 - 1000条数据 = 1000次网络往返
for (Order order : orders) {
    orderMapper.insert(order);  // 每次都单独一次JDBC round-trip
}
```

### 5.4 【强制】分页查询必须使用物理分页（LIMIT/OFFSET 或游标）

禁止在内存中对全量数据进行分页（即先查出所有数据再 subList）。当数据量增长时，内存分页会导致 OOM。

**正例**：
```java
// MyBatis 物理分页（使用 PageHelper 或手写）
Page<UserDO> page = PageHelper.startPage(pageNum, pageSize);
List<UserDO> users = userMapper.selectByCondition(query);
long total = page.getTotal();

// 手写 LIMIT/OFFSET
<select id="selectPage" resultType="UserDO">
    SELECT id, username, nickname, created_at
    FROM users
    WHERE status = #{status}
    ORDER BY id DESC
    LIMIT #{pageSize} OFFSET #{offset}
</select>
```

**反例**：
```java
// 内存分页 - 数据量大会OOM
List<UserDO> allUsers = userMapper.selectAll();         // 加载全量数据！
int start = (pageNum - 1) * pageSize;
int end = Math.min(start + pageSize, allUsers.size());
List<UserDO> pageData = allUsers.subList(start, end);   // 内存截取
```

### 5.5 【推荐】索引设计三原则：高频查询条件、排序字段、外键关联

- **WHERE 条件列**：出现在 `WHERE` 子句中的高选择性列（区分度高）应建索引
- **ORDER BY 列**：排序字段建索引可以避免 filesort
- **最左前缀原则**：联合索引 `(a, b, c)` 可服务于 `WHERE a=...`、`WHERE a=... AND b=...`、`WHERE a=... AND b=... AND c=...`
- **避免冗余索引**：已有 `(a, b)` 则不需要单独的 `(a)` 索引

### 5.6 【推荐】事务范围最小化，只包裹必要的数据库操作

`@Transactional` 的范围应尽可能小。事务持有时间越长，锁竞争越严重，数据库连接占用时间越长。

**正例**：
```java
@Service
public class OrderService {

    // 小事务：只包裹必要的DB操作
    public OrderResult placeOrder(PlaceOrderCommand cmd) {
        // 非DB操作放在事务外
        validateCommand(cmd);
        InventoryCheckResult inventory = checkInventoryViaHttp(cmd);  // HTTP调用不在事务内

        // 事务内只做核心DB写入
        return executeInTransaction(() -> {
            Order order = createOrderRecord(cmd);
            deductInventory(cmd, inventory);
            insertOrderLog(order.getId(), "CREATED");
            return OrderResult.success(order.getId());
        });
    }
}
```

### 5.7 【参考】读写分离与路由提示

对于读多写少的场景，使用 `@ReadOnly` 注解或 AOP 自动将读操作路由到从库。强一致性要求的读操作（如刚写入后立刻读取）应走主库。

---

## 6. 安全规约

安全是产品信任的基石。每一条安全规约的背后都是真实的安全事故教训。

### 6.1 【强制】用户输入绝不可信，所有外部输入必须校验和过滤

无论是 HTTP 参数、请求头、消息队列中的数据还是数据库读取的内容，只要来源于当前可信边界之外，就必须视为不可信输入。

**正例**：
```java
@PostMapping("/search")
public ApiResponse<SearchResult> search(@Valid @RequestBody SearchRequest request) {
    // @Valid 触发 JSR-303 校验（注解在Request类上）
    String keyword = request.getKeyword();

    // 二次清洗：防御XSS
    String safeKeyword = HtmlUtil.clean(keyword);

    // 长度限制
    if (safeKeyword.length() > 100) {
        throw new BadRequestException("搜索关键词过长");
    }

    // SQL注入防护：使用参数化查询（MyBatis #{}）
    return ApiResponse.success(searchService.doSearch(safeKeyword));
}
```

**反例**：
```java
// 直接拼接SQL - SQL注入漏洞
@GetMapping("/user/{id}")
public User getUser(@PathVariable String id) {
    // 如果id传入 "1 OR 1=1"，则返回所有用户数据
    return jdbcTemplate.queryForObject(
        "SELECT * FROM users WHERE id = " + id, User.class);  // 致命！
}

// 直接反射回前端 - XSS漏洞
@GetMapping("/greeting")
public String greeting(@RequestParam String name) {
    return "<h1>Hello, " + name + "</h1>";  // 如果name含<script>标签...
}
```

### 6.2 【强制】敏感数据禁止明文存储和传输

密码必须 bcrypt/scrypt/argon2 哈希存储。身份证号、手机号、银行卡号等PII数据必须加密存储（AES-256）。API传输必须 HTTPS。

**正例**：
```java
// 密码哈希存储
public User register(RegisterRequest request) {
    String hashedPassword = passwordEncoder.encode(request.getPassword());  // BCrypt
    User user = User.builder()
            .username(request.getUsername())
            .password(hashedPassword)  // 存哈希值，永远不存明文
            .build();
    return userRepository.save(user);
}

// PII字段加密存储
@Entity
public class UserProfile {
    @Column(name = "id_card_enc")
    private String idCardEncrypted;   // AES加密存储

    @Column(name = "phone_enc")
    private String phoneEncrypted;    // AES加密存储

    // 脱敏展示
    public String getMaskedPhone() {
        return Desensitizer.maskPhone(decrypt(phoneEncrypted));  // 138****1234
    }
}
```

### 6.3 【强制】鉴权拦截必须在 Filter/Interceptor 层统一实现

每个需要认证的 API 都必须经过统一的安全过滤器。禁止在 Controller 方法体内自行判断登录状态——那容易被遗漏。

**正例**：
```java
@Component
public class AuthFilter implements OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            writeUnauthorized(response, "缺少认证令牌");
            return;
        }

        Claims claims = jwtUtil.parseAndValidate(token.substring(7));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims.getSubject(), null, null)
        );
        chain.doFilter(request, response);
    }
}
```

**反例**：
```java
// 在每个Controller方法里重复鉴权 - 容易遗漏
@GetMapping("/api/user/info")
public UserInfo getUserInfo(@RequestHeader("token") String token) {
    if (!jwtUtil.validate(token)) {     // 容易被遗忘
        throw new UnauthorizedException();
    }
    // ...
}

// 某个接口忘了加鉴权
@GetMapping("/api/user/settings")       // 漏洞！任何人都能访问
public UserSettings getSettings(...) {
    // 这里没有鉴权检查！
}
```

### 6.4 【推荐】实施细粒度的权限控制（RBAC + ABAC）

除了角色级别的访问控制（RBAC），对于敏感操作还应增加基于属性的条件判断（ABAC）：如数据所有权检查、IP白名单、操作时间窗口等。

**正例**：
```java
@PreAuthorize("hasRole('ADMIN') or #userId == authentication.name")
public User updateUser(@PathVariable Long userId, @RequestBody UpdateUserDTO dto) {
    // RBAC: ADMIN角色可以编辑任何用户
    // ABAC: 普通用户只能编辑自己的资料 (#userId == authentication.name)
    return userService.update(userId, dto);
}

@PreAuthorize("@securityService.canAccessOrder(#orderId, authentication.name)")
public OrderDTO getOrder(@PathVariable Long orderId) {
    // 自定义SpEL表达式：检查订单归属权
    return orderService.getOrder(orderId);
}
```

### 6.5 【推荐】接口限流防刷，防止 DDoS 和恶意爬虫

所有对外暴露的 API 必须实施限流策略。推荐使用令牌桶或滑动窗口算法。关键接口（登录、注册、短信发送）应有更严格的频率限制。

**正例**：
```java
// 基于Redis + Lua脚本的滑动窗口限流
@Component
public class RateLimiter {

    public boolean allow(String key, int maxRequests, int windowSeconds) {
        String script = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if count > tonumber(ARGV[1]) then
                return 0
            end
            return 1
            """;
        Long result = redisTemplate.execute(
                new DefaultRedisScript<>(script, Long.class),
                List.of("rate:" + key),
                String.valueOf(maxRequests),
                String.valueOf(windowSeconds));
        return result != null && result == 1;
    }
}

// 应用
@PostMapping("/login")
public Result login(@RequestBody LoginRequest req, HttpServletRequest httpRequest) {
    String clientIp = getClientIp(httpRequest);
    if (!rateLimiter.allow("login:" + clientIp, 5, 60)) {
        return Result.fail("操作过于频繁，请稍后再试");
    }
    // ... 登录逻辑
}
```

### 6.6 【参考】安全响应头配置

Web应用应配置以下安全响应头：`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Content-Security-Policy`、`Strict-Transport-Security`。Spring Security 可一键配置。

---

## 7. 日志规约

日志是线上问题排查的第一手段。好的日志能在几分钟内定位问题根源；糟糕的日志则让排查过程变成猜谜游戏。

### 7.1 【强制】禁止在生产环境使用 System.out.println / System.err / printStackTrace()

这些输出无法按级别控制、无法输出到集中式日志系统、无法按请求追踪，且性能低下（System.out 是同步的）。

**正例**：
```java
@Slf4j
public class OrderService {

    public void processOrder(Order order) {
        log.info("开始处理订单: orderId={}, userId={}", order.getId(), order.getUserId());
        try {
            // ...
            log.info("订单处理完成: orderId={}, status={}", order.getId(), order.getStatus());
        } catch (PaymentException e) {
            log.error("订单支付失败: orderId={}, amount={}", order.getId(), order.getTotalAmount(), e);
            // 注意：最后一个参数是异常对象，SLF4J会自动打印完整堆栈
        }
    }
}
```

**反例**：
```java
// 调试遗留
System.out.println("Processing order: " + order.getId());

// 异常处理偷懒
catch (Exception e) {
    e.printStackTrace();  // 输出到stdout，没有级别控制，丢失上下文
}
```

### 7.2 【强制】日志必须包含关键上下文，禁止输出无意义的空日志

每条日志至少回答：**谁（用户/请求）、做了什么、涉及哪些关键实体ID、结果如何**。禁止出现 `"success"` 、`"error"` 、`"done"` 这样毫无信息的日志。

**正例**：
```java
// 包含丰富上下文的日志
log.info("订单创建成功: orderId={}, userId={}, skuCount={}, totalAmount={}, paymentMethod={}",
        order.getId(), order.getUserId(),
        order.getItems().size(),
        order.getTotalAmount(),
        order.getPaymentMethod());

log.warn("库存不足预警: skuId={}, requested={}, available={}, orderId={}",
        skuId, requestedQty, availableQty, orderId);

log.error("第三方支付回调验签失败: orderId={}, payChannel={}, signSource={}, expectedSign={}",
        orderId, payChannel, receivedSign, calculatedSign);
```

**反例**：
```java
log.info("success");                          // 什么成功了？？
log.info("处理完成");                         // 处理什么？
log.error("出错了");                           // 什么错？
log.debug("user: " + user + " order: " + order);  // toString()可能很大且无结构
```

### 7.3 【强制】ERROR 级别只在确实发生错误时使用

日志级别应严格对应事件的严重程度：

| 级别 | 使用场景 |
|------|---------|
| ERROR | 影响用户功能的错误（需要值班人员关注） |
| WARN  | 可自愈的异常情况（需要关注但无需立即处理）|
| INFO  | 关键业务节点（用于追踪业务流程） |
| DEBUG | 开发调试信息（生产环境通常关闭） |

**正例**：
```java
// ERROR - 支付失败了，用户无法完成下单
log.error("支付渠道调用失败: orderId={}, channel={}, errorCode={}, errorMsg={}",
        orderId, channel, resp.getCode(), resp.getMessage(), ex);

// WARN - 重试后恢复了
log.warn("缓存读取失败，降级到数据库查询: key={}, fallbackSuccess={}", key, fallbackOk);

// INFO - 正常业务流程节点
log.info("用户登录成功: userId={}, ip={}, loginMethod={}", userId, ip, method);
```

**反例**：
```java
// 不应该是ERROR的情况
log.error("用户输入的用户名已存在");              // 这是正常的业务校验结果，用INFO/WARN即可
log.error("缓存未命中，查询数据库");              // 这是正常的缓存穿透，用DEBUG即可

// 应该是ERROR却用了WARN
log.warn("磁盘空间不足，文件写入失败");           // 这会影响功能，应为ERROR
```

### 7.4 【推荐】使用 MDC（Mapped Diagnostic Context）实现请求链路追踪

每次请求入口处将 requestId、userId 等信息放入 MDC，使得该请求产生的所有日志都可以通过 requestId 关联检索。

**正例**：
```java
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws Exception {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }

        MDC.put("requestId", requestId);
        MDC.put("clientIp", getClientIp(request));
        MDC.put("uri", request.getRequestURI());

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();  // 必须清理，防止线程池复用导致MDC泄漏
        }
    }
}

// logback pattern 中使用 %X{requestId}
// <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{requestId}] %-5level %logger{36} - %msg%n</pattern>
```

### 7.5 【推荐】敏感数据脱敏后才记入日志

日志中禁止出现完整的密码、身份证号、银行卡号、Token、SessionID等敏感信息。输出前必须脱敏处理。

**正例**：
```java
// 脱敏工具类
public class LogDesensitizer {
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "****";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 8) return "********";
        return idCard.substring(0, 4) + "********" + idCard.substring(idCard.length() - 4);
    }

    public static String maskBankCard(String cardNo) {
        if (cardNo == null || cardNo.length() < 8) return "****";
        return "****" + cardNo.substring(cardNo.length() - 4);
    }
}

// 使用
log.info("用户绑定银行卡: userId={}, cardNo={}, phone={}",
        userId,
        LogDesensitizer.maskBankCard(request.getCardNumber()),
        LogDesensitizer.maskPhone(request.getPhone()));
```

### 7.6 【参考】日志聚合与告警

推荐接入 ELK（Elasticsearch + Logstash + Kibana）或 Loki + Grafana 技术栈进行日志集中化管理。针对 ERROR 日志配置实时告警（钉钉/企微/邮件），确保关键异常在分钟级被发现。

---

## 8. 工程结构规约

良好的工程结构是项目长期可维护的基础。XX科技采用分层架构 + DDD（领域驱动设计）思想指导工程组织。

### 8.1 【强制】遵循标准分层架构，禁止跨层直接访问

项目必须按以下层次组织，依赖方向单向向下：

```
controller 层（接口适配）
    ↓ 调用
service 层（业务逻辑）
    ↓ 调用
repository/mapper 层（数据访问）
    ↓ 访问
database / external-service
```

**正例**：
```java
// Controller - 只做参数接收和响应封装
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;  // 只依赖Service层

    @PostMapping
    public ApiResponse<OrderVO> createOrder(@Valid @RequestBody CreateOrderRequest req) {
        OrderVO result = orderService.createOrder(req);  // 委托给Service
        return ApiResponse.success(result);
    }
}

// Service - 核心业务逻辑
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;   // 只依赖Repository
    private final InventoryClient inventoryClient;   // 外部服务客户端
    private final OrderEventPublisher eventPublisher;

    @Transactional
    public OrderVO createOrder(CreateOrderRequest req) {
        // 业务逻辑编排
    }
}
```

**反例**：
```java
// Controller 直接操作 Mapper - 跨层访问
@RestController
public class UserController {
    private final UserMapper userMapper;  // 错误！Controller不应直接依赖Mapper

    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) {
        return userMapper.selectById(id);  // 跳过了Service层
    }
}

// Service 中直接操作 HttpServletRequest - 层次混淆
@Service
public class UserServiceImpl implements UserService {
    public User getCurrentUser(HttpServletRequest request) {  // Service不应依赖Web层对象
        String token = request.getHeader("Authorization");
        // ...
    }
}
```

### 8.2 【强制】循环依赖被严格禁止

Spring Bean 之间的循环依赖（A→B→A）表明设计存在结构性问题。必须通过引入中间抽象接口、事件驱动或重构来解决。

**正例**：
```java
// 通过事件解耦循环依赖
@Service
public class OrderServiceImpl implements OrderService {

    private final ApplicationEventPublisher eventPublisher;

    public void completeOrder(Long orderId) {
        // ... 完成订单逻辑
        eventPublisher.publishEvent(new OrderCompletedEvent(orderId));  // 发布事件
    }
}

@Service
public class InventoryServiceImpl implements InventoryService {

    @EventListener
    public void onOrderCompleted(OrderCompletedEvent event) {
        // 通过事件监听响应，不再需要直接依赖OrderService
        releaseReservedInventory(event.getOrderId());
    }
}
```

**反例**：
```java
// 循环依赖 - Spring Boot 2.6+ 默认不允许
@Service
public class OrderService {
    @Autowired
    private InventoryService inventoryService;  // Order -> Inventory
    // ...
}

@Service
public class InventoryService {
    @Autowired
    private OrderService orderService;          // Inventory -> Order  ← 循环！
    // ...
}
```

### 8.3 【强制】配置文件与环境隔离，禁止硬编码敏感信息

数据库密码、API Key、密钥等敏感信息必须通过环境变量或配置中心注入。绝对禁止将敏感信息提交到代码仓库。

**正例**：
```yaml
# application.yml - 只放占位符
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:appdb}
    username: ${DB_USERNAME:app}
    password: ${DB_PASSWORD:secret}

aliyun:
  oss:
    access-key-id: ${OSS_ACCESS_KEY_ID}
    access-key-secret: ${OSS_ACCESS_KEY_SECRET}
```

**反例**：
```yaml
# 绝对禁止！
spring:
  datasource:
    password: 'P@ssw0rd123!'          // 明文密码提交到Git
    url: jdbc:mysql://192.168.1.100:3306/appdb  // 内网IP硬编码

aliyun:
  sms:
    access-key: 'LTAI4Gxxxxxxxxxxxx'  // AK泄露风险
    secret: 'secret_key_here'
```

### 8.4 【推荐】API 版本化，通过 URL 路径或 Header 管理

对外提供的 RESTful API 必须包含版本号。推荐 URL 路径方式 `/api/v1/`，便于网关层面的路由和灰度发布。

**正例**：
```java
// v1 API
@RestController
@RequestMapping("/api/v1/users")
public class UserV1Controller { ... }

// v2 API（兼容升级）
@RestController
@RequestMapping("/api/v2/users")
public class UserV2Controller {
    // 新增字段、修改返回结构等
}
```

### 8.5 【推荐】统一 API 响应格式，所有接口遵循相同的数据包装

所有 RESTful API 的响应体必须使用统一的包装结构，包含：状态码、消息、数据、时间戳（可选）、请求追踪ID（可选）。

**正例**：
```java
@Data
@Builder
public class ApiResponse<T> {
    private String code;       // "200"=成功, "400"=参数错误, "401"=未认证, etc.
    private String message;    // 人类可读的消息
    private T data;            // 业务数据
    private long timestamp;    // 服务端响应时间戳
    private String traceId;    // 请求追踪ID
}

// 使用示例
return ApiResponse.<OrderVO>builder()
        .code("200")
        .message("订单创建成功")
        .data(orderVO)
        .timestamp(System.currentTimeMillis())
        .traceId(MDC.get("requestId"))
        .build();
```

### 8.6 【参考】模块化与微服务边界划分

当单体应用的服务规模增长到一定程度（建议指标：部署包超过 200MB、开发团队超过 15 人、编译时间超过 3 分钟），应评估微服务拆分的可行性。拆分原则按领域边界（DDD Bounded Context）而非技术分层。

---

## 附录A：规约等级说明

| 等级 | 含义 | 违规后果 |
|------|------|---------|
| **【强制】** | 必须遵守，否则 Code Review 不予通过 | 阻塞合并，CI门禁卡住 |
| **【推荐】** | 强烈建议遵守，特殊情况需在MR中说明理由 | 需要Reviewer确认 |
| **【参考】** | 最佳实践建议，供团队学习和参考 | 不强制，鼓励采纳 |

## 附录B：相关文档

- [XX科技代码审查 Checklist](../review-checklist.md)
- [XX科技 Git 分支管理规范](../git-workflow.md)
- [XX技术 API 设计指南](../api-design-guide.md)
- [XX科技 CI/CD 流水线手册](../cicd-handbook.md)

---

> **版权所有** &copy; 2026 XX科技有限公司 - 技术标准委员会
> **最后更新**: 2026-06-01 | **下次评审**: 2026-12-01

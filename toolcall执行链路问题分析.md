# Toolcall 执行链路问题分析

## 一、核心机制分析

### 1.1 异步执行模式

整个系统采用**异步执行 + 轮询查询**的模式：

```
前端请求 → 后端立即返回 planId → 后端异步执行 → 前端轮询获取状态
```

**关键特点**：
- 后端不等待执行完成就返回响应
- 前端通过轮询主动获取执行状态
- 执行状态存储在数据库中，通过 API 查询

### 1.2 时序关系图

```
时间轴：
T0: 前端发送请求
T1: 后端生成 planId
T2: 后端返回 planId（立即返回，不等待）
T3: 前端收到 planId，开始轮询
T4: 后端异步任务开始执行
T5: recordPlanExecutionStart() 创建数据库记录
T6: 前端轮询查询数据库（可能早于 T5）
```

**问题点**：T3 和 T5 之间存在时序竞争条件。

### 1.3 数据库记录创建时机

**关键代码位置**：
- `AbstractPlanExecutor.executeAllStepsAsync()` (line 269)
- 在 `CompletableFuture.supplyAsync()` 的异步任务中调用
- `recorder.recordPlanExecutionStart()` 创建数据库记录

**执行流程**：
1. `executeByToolNameAsync()` 生成 planId
2. 调用 `executePlanTemplate()` 创建 `CompletableFuture`
3. **立即返回** planId（此时数据库记录还未创建）
4. 异步任务提交到线程池
5. 线程池调度执行时，才调用 `recordPlanExecutionStart()`

**问题**：数据库记录的创建依赖于线程池的调度，存在不可控的延迟。

### 1.4 前端轮询机制

**轮询流程**：
1. 收到 planId 后立即调用 `handlePlanExecutionRequested(planId)`
2. `trackPlan(planId)` 添加到跟踪列表
3. **立即执行第一次轮询**（不等待间隔）
4. 启动定时器，每 1 秒轮询一次

**重试机制**：
- 如果查询返回 null（记录不存在），最多重试 10 次
- 每次重试间隔 1 秒
- 如果 10 次后仍找不到，放弃跟踪

**问题**：
- 如果线程池繁忙，数据库记录可能在 10 秒后才创建
- 重试次数可能不够
- 没有指数退避，固定 1 秒间隔可能不够灵活

### 1.5 响应式更新机制

**watchEffect 依赖追踪**：
```typescript
watchEffect(() => {
  const records = planExecution.planExecutionRecords
  const recordsSize = records.size  // 强制追踪 Map 大小
  const recordsArray = Array.from(records.entries())  // 强制追踪 Map 内容
  // ...
})
```

**更新流程**：
1. 轮询成功 → 更新 `planExecutionRecords` Map
2. Map 变化触发 `watchEffect`
3. `watchEffect` 查找对应的 dialog 和 message
4. 调用 `updateMessageWithPlanRecord()` 更新 UI

**潜在问题**：
- Map 的响应式追踪可能不够精确
- 如果 Map 的 key 不匹配（planId vs rootPlanId），可能找不到记录
- watchEffect 的依赖追踪可能遗漏某些变化

## 二、问题根源分析

### 2.1 时序竞争条件（Race Condition）

**核心问题**：
```
后端返回 planId 的时机 < 数据库记录创建的时机
前端轮询的时机可能早于数据库记录创建
```

**具体表现**：
1. 后端生成 planId 并立即返回
2. 前端收到 planId 后立即开始轮询
3. 但数据库记录还在等待线程池调度
4. 第一次轮询返回 null
5. 如果线程池繁忙，可能需要多次重试才能找到记录

**影响因素**：
- 线程池的繁忙程度
- 数据库事务的提交时机
- 网络延迟
- 前端轮询的时机

### 2.2 重试机制不足

**当前重试策略**：
- 最多重试 10 次
- 固定间隔 1 秒
- 没有指数退避

**问题**：
- 如果线程池非常繁忙，10 秒可能不够
- 固定间隔不够灵活，可能浪费资源或等待不足
- 没有根据实际情况动态调整

### 2.3 PlanId 匹配问题

**多键匹配逻辑**：
```typescript
// 使用 rootPlanId 或 currentPlanId 作为键
const recordKey = details.rootPlanId || details.currentPlanId

// 如果 planId 和 recordKey 不同，同时存储两个键
if (planId !== recordKey) {
  planExecutionRecords.set(planId, details)
  planExecutionRecords.set(recordKey, details)
}
```

**问题**：
- 前端收到的 planId 可能是 rootPlanId
- 但数据库记录可能用 currentPlanId 作为主键
- 虽然有多键匹配，但 watchEffect 中的查找逻辑可能不够完善

### 2.4 watchEffect 依赖追踪问题

**当前实现**：
```typescript
watchEffect(() => {
  const records = planExecution.planExecutionRecords
  const recordsSize = records.size
  const recordsArray = Array.from(records.entries())
  // ...
})
```

**潜在问题**：
1. **Map 的响应式追踪**：Vue 3 对 Map 的响应式支持可能不够完善
2. **依赖遗漏**：如果 Map 的 key 变化但 value 引用不变，可能不会触发更新
3. **深层嵌套**：PlanExecutionRecord 包含深层嵌套对象，响应式追踪可能不完整

### 2.5 初始状态显示问题

**当前逻辑**：
```typescript
// 如果消息有 planExecution 但记录未找到，保持初始状态
if (!recordEntry) {
  if (messagePlanExecution && messagePlanExecution.status === 'running') {
    // 保持初始状态可见
    continue
  }
  // 否则跳过
  continue
}
```

**问题**：
- 初始状态可能不够明显
- 如果第一次轮询失败，用户可能看不到任何反馈
- 没有明确的"等待中"状态提示

## 三、一劳永逸的解决方案思路

### 3.1 方案一：预创建数据库记录（推荐）

**核心思路**：在返回 planId 之前就创建数据库记录

**实现方式**：
1. 在 `executePlanTemplate()` 中，生成 planId 后立即创建数据库记录
2. 记录初始状态：`status = 'pending'`, `completed = false`
3. 异步执行开始后，更新记录状态为 `'running'`

**优点**：
- 彻底解决时序竞争问题
- 前端轮询时总能找到记录
- 用户体验更好，立即看到执行状态

**缺点**：
- 如果执行失败，需要清理未执行的记录
- 需要处理执行未启动的情况

**实现要点**：
```java
// 在 executePlanTemplate() 中
String planId = planIdDispatcher.generatePlanId();

// 立即创建数据库记录（同步操作）
recorder.recordPlanExecutionStart(planId, title, userRequest, 
    steps, null, planId, null);  // 初始状态：pending

// 然后异步执行
CompletableFuture<PlanExecutionResult> future = 
    planningCoordinator.executeByPlan(...);

// 执行开始后更新状态为 running
future.whenComplete((result, throwable) -> {
    if (throwable == null) {
        // 更新状态
    }
});
```

### 3.2 方案二：增强重试机制

**核心思路**：改进重试策略，提高容错能力

**实现方式**：
1. **指数退避**：重试间隔逐渐增加（1s, 2s, 4s, 8s...）
2. **增加重试次数**：从 10 次增加到 30 次或更多
3. **动态调整**：根据网络延迟和服务器响应动态调整
4. **超时机制**：设置总超时时间（如 60 秒）

**优点**：
- 不需要修改后端逻辑
- 提高容错能力
- 适应不同的网络和服务器条件

**缺点**：
- 仍然存在时序竞争，只是提高了容错
- 用户体验可能不够好（需要等待）

**实现要点**：
```typescript
const MAX_RETRY_ATTEMPTS = 30
const MAX_TOTAL_TIMEOUT = 60000  // 60秒

const pollPlanStatus = async (planId: string, startTime: number): Promise<void> => {
  // 检查总超时
  if (Date.now() - startTime > MAX_TOTAL_TIMEOUT) {
    // 超时处理
    return
  }
  
  const retryCount = planRetryAttempts.get(planId) || 0
  const backoffDelay = Math.min(1000 * Math.pow(2, retryCount), 10000)  // 指数退避，最大10秒
  
  if (!details && retryCount < MAX_RETRY_ATTEMPTS) {
    setTimeout(() => {
      pollPlanStatus(planId, startTime)
    }, backoffDelay)
  }
}
```

### 3.3 方案三：WebSocket 实时推送

**核心思路**：后端主动推送状态更新，替代轮询

**实现方式**：
1. 前端建立 WebSocket 连接
2. 后端执行状态变化时主动推送
3. 前端接收推送更新 UI

**优点**：
- 实时性好，无延迟
- 减少服务器压力（不需要频繁轮询）
- 彻底解决时序问题

**缺点**：
- 需要实现 WebSocket 服务
- 需要处理连接断开和重连
- 架构改动较大

**实现要点**：
```java
// 后端：状态变化时推送
@EventListener
public void onPlanStatusChange(PlanStatusChangeEvent event) {
    webSocketService.sendToClient(event.getPlanId(), event.getStatus());
}
```

```typescript
// 前端：接收推送
const ws = new WebSocket('/ws/plan-status')
ws.onmessage = (event) => {
  const { planId, status } = JSON.parse(event.data)
  updatePlanExecutionRecord(planId, status)
}
```

### 3.4 方案四：混合方案（推荐用于过渡）

**核心思路**：结合预创建记录 + 增强重试 + 优化响应式

**实现方式**：
1. **后端**：预创建数据库记录（方案一）
2. **前端**：增强重试机制（方案二）
3. **前端**：优化 watchEffect 依赖追踪
4. **前端**：改进初始状态显示

**优点**：
- 多重保障，提高可靠性
- 可以逐步实施
- 兼容现有架构

**缺点**：
- 实现复杂度较高
- 需要前后端配合

### 3.5 方案五：优化响应式追踪

**核心思路**：改进 watchEffect 的依赖追踪机制

**实现方式**：
1. **使用 watch 替代 watchEffect**：明确指定依赖
2. **强制触发更新**：使用 `triggerRef()` 或 `nextTick()`
3. **深度监听**：使用 `watch(..., { deep: true })`
4. **手动触发**：在关键位置手动触发更新

**优点**：
- 不需要修改后端
- 提高响应式可靠性
- 解决 UI 不更新的问题

**缺点**：
- 不能解决时序竞争问题
- 只是改善症状，不是根本解决

**实现要点**：
```typescript
// 使用 watch 明确依赖
watch(
  () => planExecution.planExecutionRecords,
  (newRecords, oldRecords) => {
    // 明确处理变化
    const newKeys = new Set(newRecords.keys())
    const oldKeys = new Set(oldRecords?.keys() || [])
    
    // 找出新增和更新的记录
    for (const key of newKeys) {
      if (!oldKeys.has(key) || newRecords.get(key) !== oldRecords?.get(key)) {
        updateMessageWithPlanRecord(key, newRecords.get(key))
      }
    }
  },
  { deep: true, immediate: true }
)
```

## 四、推荐方案对比

| 方案 | 解决时序问题 | 实现难度 | 用户体验 | 架构改动 | 推荐度 |
|------|------------|---------|---------|---------|--------|
| 方案一：预创建记录 | ✅ 完全解决 | 中等 | ⭐⭐⭐⭐⭐ | 小 | ⭐⭐⭐⭐⭐ |
| 方案二：增强重试 | ⚠️ 部分缓解 | 低 | ⭐⭐⭐ | 无 | ⭐⭐⭐ |
| 方案三：WebSocket | ✅ 完全解决 | 高 | ⭐⭐⭐⭐⭐ | 大 | ⭐⭐⭐⭐ |
| 方案四：混合方案 | ✅ 完全解决 | 高 | ⭐⭐⭐⭐⭐ | 中等 | ⭐⭐⭐⭐⭐ |
| 方案五：优化响应式 | ❌ 不解决 | 低 | ⭐⭐ | 无 | ⭐⭐ |

## 五、最佳实践建议

### 5.1 短期方案（快速修复）

1. **增加重试次数和超时时间**
   - 将 MAX_RETRY_ATTEMPTS 从 10 增加到 30
   - 添加总超时时间（60 秒）
   - 实现指数退避

2. **优化初始状态显示**
   - 在消息中明确显示"等待执行中"状态
   - 添加加载动画
   - 显示重试次数

3. **改进 watchEffect**
   - 添加更详细的日志
   - 使用 `nextTick()` 确保更新时机
   - 添加手动触发机制

### 5.2 长期方案（根本解决）

1. **实施方案一：预创建数据库记录**
   - 在返回 planId 前创建记录
   - 设置初始状态为 'pending'
   - 执行开始后更新为 'running'

2. **考虑方案三：WebSocket 实时推送**
   - 如果系统需要更好的实时性
   - 减少服务器压力
   - 提升用户体验

### 5.3 监控和诊断

1. **添加详细日志**
   - 记录 planId 生成时间
   - 记录数据库记录创建时间
   - 记录前端轮询时间
   - 计算时间差，识别问题

2. **添加性能指标**
   - 平均等待时间
   - 重试次数统计
   - 失败率监控

3. **添加用户反馈**
   - 显示"正在连接服务器..."
   - 显示重试状态
   - 显示预计等待时间

## 六、总结

**核心问题**：
- 时序竞争条件：后端返回 planId 和数据库记录创建之间存在时间差
- 重试机制不足：固定重试次数和间隔可能不够
- 响应式追踪问题：Vue 3 的响应式机制可能不够完善

**推荐解决方案**：
1. **短期**：增强重试机制 + 优化初始状态显示
2. **长期**：预创建数据库记录（方案一）或 WebSocket 实时推送（方案三）

**关键原则**：
- 确保前端轮询时总能找到记录（预创建）
- 提高容错能力（增强重试）
- 改善用户体验（优化显示）
- 加强监控和诊断（日志和指标）


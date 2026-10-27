# QuietIt

移除 Forge 事件总线字节码转换器里两处 `Logger#error(...)` 调用：

目标类：`net.minecraftforge.eventbus.EventSubclassTransformer`（Forge 1.20.1 / eventbus 6.0.5）

| 方法 | 被移除的调用 |
| --- | --- |
| `transform(ClassNode, Type)` | `LOGGER.error(EVENTBUS, "An error occurred building event handler", e)` |
| `buildEvents(ClassNode)` | `LOGGER.error(EVENTBUS, "Could not find parent {} for class {} in classloader {} on thread {}", ...)` |

## 为什么是 JavaAgent，而不是 ASM CoreMod

这是查过 ModLauncher 10.x 源码后的结论：

- 该类由 eventbus 自己的 `ILaunchPluginService`（`net.minecraftforge.eventbus.service.ModLauncherService`）
  实例化，ModLauncher 把 eventbus 作为 **PLUGIN 层**模块加载。
- `ModuleLayerHandler.buildLayer(Layer.PLUGIN)` 用的是普通 `ModuleClassLoader`，**不会**走
  `TransformingClassLoader`；而 mod 的 `ITransformationService#transformers()` / `IClassTransformer`
  只作用于 **GAME 层**的类转换。也就是说 CoreMod 的 transformer 根本看不到这个类。
- `-javaagent` 注册的 `ClassFileTransformer` 由 JVM 在每次 `defineClass` 时调用，与类加载器/模块层无关，
  拿到的还是 jar 里的原始字节，因此能稳定地在它被定义前改写。
- Mixin 走的是同一套 launch plugin 机制，同样碰不到它。

所以本项目的实际功能载体是独立 agent jar `quietit-agent`。

## 构建

```bash
gradlew agentJar        # 只构建 agent：build/libs/quietit-1.20.1-1.0.0-agent.jar
gradlew build           # 同时构建普通 mod jar、sources jar 和 agent jar
```

agent jar 自带并重定位（`org.objectweb.asm` -> `me.kall.quietit.agent.shaded.asm`）ASM 9.7.1，
不依赖 Forge 的模块路径，也不污染 mod jar。

## 使用

在游戏 JVM 参数中加入：

```
-javaagent:<绝对路径>/quietit-1.20.1-1.0.0-agent.jar
```

- 官方启动器：安装 -> 更多选项 -> JVM 参数。
- Prism / CurseForge / ATLauncher 等：实例的 “JVM 参数/Java 参数”。
- 任意环境也可以用环境变量 `JAVA_TOOL_OPTIONS=-javaagent:...`（会对该环境启动的所有 JVM 生效）。

开发环境已接好：`gradlew runClient` / `gradlew runServer` 会先构建 agent 再自动带上该参数。

启动时 console 会打印：

```
[QuietIt] Java agent active (premain); target: net.minecraftforge.eventbus.EventSubclassTransformer
[QuietIt] removed Logger#error(...)V from ...#transform(...)
[QuietIt] removed Logger#error(...)V from ...#buildEvents(...)
[QuietIt] patched net.minecraftforge.eventbus.EventSubclassTransformer: removed 2 Logger#error call(s), 0 remaining
```

## 实现方式

- `QuietItAgent`：`premain` 注册 transformer；同时提供 `agentmain`，在动态 attach 时对已加载的类做
  retransform（`Can-Retransform-Classes: true`）。
- `EventSubclassTransformerPatcher`：ASM Tree API 遍历方法，匹配
  `INVOKEINTERFACE org/apache/logging/log4j/Logger.<error>(...)V`，按描述符把 receiver 和参数依次 `POP` /
  `POP2` 掉，再删除调用指令。栈深度与每条指令的帧状态不变，因此无需重算 `StackMapTable`，也不会改动
  `max_locals`。
- 失败安全：任何异常都只会打印警告并返回 `null`（保持原类），不会让游戏崩溃。
- `-Dquietit.dump.dir=<dir>`：把改写后的 class 文件导出，便于 `javap -p -c` 检查（调试用）。

## 验证

```bash
gradlew agentSmokeTest          # 带 agent：真实 eventbus 类加载、0 个 Logger#error、触发错误路径
gradlew agentSmokeTestControl   # 不带 agent：同样路径会打印两条 ERROR，作为对照
```

`agentSmokeTest` 会：

1. 加载真实的 `EventSubclassTransformer`；
2. 用 ASM 检查导出的字节码中 `Logger#error` 调用数为 0；
3. 用一个父类不存在的 `ClassNode` 调用 `transform`，走通两条错误分支，确认类通过校验且行为不变。

本仓库还做过一次完整的 Forge 开发服务器启动验证（Forge 47.4.28 / MC 1.20.1 / Java 24）：
`gradlew runServer` 日志出现上面的 `[QuietIt] patched ... removed 2 ... 0 remaining`，
随后服务器正常输出 `Done (9.442s)!`。

## 说明

- 只删除 `error` 两处，`Logger#debug(...)` 保持原样。
- 行为变化：两条错误分支的控制流不变（`buildEvents` 的异常照旧抛出，`transform` 照旧吞掉并返回节点），
  只是不再写日志。
- 在 eventbus 6.0.5（1.20.1 正式环境）与 6.2.x（本仓库开发 classpath）上都验证过；若以后该类结构变化，
  agent 会打印 `removed N / remaining M` 并保持失败安全。
- 普通 mod jar 不包含该功能，agent jar 是独立文件，直接放进 `mods/` 不会生效，必须挂 JVM 参数。
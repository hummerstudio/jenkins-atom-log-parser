# jenkins-atom-log-parser

按规则扫构建日志，把命中变成数字和一个判定。

log-parser 插件是在 master 上轮询正在跑的构建日志；原子只看到交给它的那份日志，所以这里读 `build.log`
（runner 会写，默认尾部 2 MiB）或 workspace 里的文件，一次读完、应用规则、报计数和头几条命中的行。

规则语义是重点：

```json
{
  "error": "ERROR|Exception",
  "npe": {"pattern": "NullPointerException", "max": 0},
  "tests": {"pattern": "Tests run:", "min": 1},
  "deprecation": {"pattern": "deprecated", "max": 3, "level": "warn"}
}
```

- 只给正则 = 一条都不能命中（超过 0 就失败）
- `min` / `max` = 期望的条数区间
- `level: warn` = 超了只告警，不失败（"容忍但记账"的规则）

命中行带**行号**放进 `matches` 出参，失败时第一条命中会写进报错里，不用去翻全量日志。

```bash
mvn verify   # build + src/test/java/atom/LogParserIT.java
```

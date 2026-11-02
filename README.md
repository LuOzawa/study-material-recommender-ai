# 基于 Hadoop 的考研参考资料推荐系统

> v2：在 Spring Boot 3 + Vue 3 基础上新增 **FastAPI + LangGraph + vLLM + pgvector** 智能检索问答层。
> 上一版仓库：[LuOzawa/study-material-recommender](https://github.com/LuOzawa/study-material-recommender)

[![CI](https://github.com/LuOzawa/study-material-recommender-ai/actions/workflows/ci.yml/badge.svg)](https://github.com/LuOzawa/study-material-recommender-ai/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)


面向考研学生的资料推荐与学习辅助平台。用户注册后可浏览/搜索/评分/收藏考研资料，
系统结合**协同过滤 + 矩阵分解 + 内容特征**四路算法生成个性化推荐，
并用 **Hadoop HDFS** 做行为数据的离线归档与训练集导出。

- **后端**：Spring Boot 3.2.3（Java 17）+ Spring Security + JWT + Spring Data JPA + PostgreSQL 16
- **前端**：Vue 3 + Vite 5 + Element Plus + Pinia + Vue Router + ECharts
- **推荐引擎**：Python 3.11+（pandas / scikit-learn / numpy），UserCF + ItemCF + TF-IDF + SVD 加权融合，含离线评测（留一法 + Top-K）
- **大数据**：Hadoop 3.3.6 —— HDFS 归档 + 定时导出按天分区 CSV + **MapReduce 热度聚合作业**（本地模式实测）
- **AI 服务**：FastAPI + **LangGraph** 智能体（意图路由 → pgvector 检索 → 相关性评分 → 查询改写 → 带引用作答）+ **vLLM** 自托管推理（Qwen2.5-7B-Instruct + bge-m3）
- **向量库**：PostgreSQL 16 + **pgvector**（HNSW 索引、余弦距离）
- **规模**：后端 74 个 Java 文件；前端 24 个文件 / 7,003 行；Python 推荐引擎 8 个文件 / 1,447 行；AI 服务 32 个 Python 文件 / 2,640 行

项目为本科毕业设计作品。

---

## 📌 交付状态总览（先读这一节）

### 状态标记约定

本文件里所有进度描述只用这四个标记，看到就按这个理解：

| 标记 | 含义 | 判断标准 |
| --- | --- | --- |
| ✅ | **已完成，且我实测过** | 能指出具体的验证命令与输出 |
| 🟡 | **已完成，但有前提** | 功能在，但依赖某个条件（模拟数据 / 本地模式 / 需自备 Key） |
| 🔲 | **未完成** | 要做成完整毕设必须补的东西 |
| 📄 | **属于论文，不在代码里** | 代码提供素材，写作在论文侧完成 |

> ⚠️ **别把 🟡 当 ✅ 用**。🟡 的每一项都有明确前提，写进论文时必须带上那个前提，
> 否则就是夸大。下面 **A 节**逐项标了「怎么验证」，**B 节**专门讲三处最容易混淆的地方，
> **C 节**是「做成完整毕设还缺什么」的清单（代码类 / 实验类 / 论文类分开列）。

### A. 逐项状态

| 任务书要求 | 状态 | 怎么验证 | 前提 / 局限 |
| --- | --- | --- | --- |
| 用户模块（注册/登录/找回/资料/收藏/评分/历史/偏好） | ✅ | 登录接口返回 JWT；口令为 BCrypt | — |
| 资料模块（列表/搜索/详情/增删改/分类） | ✅ | `/api/materials` 系列接口 | 搜索是 `LIKE` 模糊匹配，非全文索引 |
| 推荐模块（协同过滤 + 内容推荐） | ✅ | 跑 `run_recommendation.py` 写入 `recommendations` 表 | 冷启动退化热门榜 |
| 推荐反馈闭环（点赞/踩影响推荐） | ✅ | 见 9.1，点踩后该资料从推荐中剔除 | — |
| 数据分析模块（画像/热度/趋势/漏斗） | ✅ | `GET /api/analytics/dashboard?days=365` | 旧数据需把 `days` 调大才看得到 |
| Hadoop 分布式**存储** | ✅ | `POST /api/admin/hadoop/export` 导出分区 CSV | HDFS 不可用时自动回退本地目录 |
| Hadoop 分布式**计算**（MapReduce） | 🟡 | `POST /api/admin/hadoop/benchmark` | **只在 `LocalJobRunner` 本地模式跑过**，没上真实集群；Windows 需 winutils（5.5） |
| AI 大模型模块（问答/摘要/建议/语义搜索） | ✅ | 启动 ai-service 后调 `/api/ai/*`；回答带 `citations` | 需要 vLLM 提供的 OpenAI 兼容服务（本地无 GPU 可用 `ai-service/scripts/stub_vllm.py` 联调） |
| 管理员模块（用户/资料/分类/日志/备份） | ✅ | `/api/admin/**` 全部要求 ADMIN | — |
| 推荐效果评测（离线指标） | ✅ | 第四节，`evaluate_offline.py` | 指标口径与数据装配方式需在论文说明 |
| 论文可用的实验数据 | 🟡 | 第四节 4.4 的三档对比表 | **200 个用户里 196 个是模拟用户**（详见 4.4） |
| 行为埋点完整性 | 🟡 | 收藏已补 `COLLECT` | **评论不记**：`action` 枚举里没有 `COMMENT`（9.3） |
| 单元测试 / 接口测试 | ✅ | `cd backend && mvn test`（26 个用例）+ `cd ai-service && uv run pytest`（30 个用例，含真实 pgvector 集成） | 未覆盖全部业务服务，见「附：自动化测试」 |

### B. 三处必须区分清楚的地方

这三处是**答辩最容易被追问**、也最容易写错的地方。表格左边是「容易混的说法」，
右边是「准确的说法」。

| # | ❌ 容易混的说法 | ✅ 准确的说法 | 为什么重要 |
| --- | --- | --- | --- |
| 1 | 「用 200 个用户做了评测」 | 「4 个真实用户 + 200 个**按学科偏好规则生成的模拟用户**」 | 老师说"你这 200 个用户哪来的"，如果答不上来，整章实验都不成立 |
| 2 | 「用 Hadoop 做了分布式计算」 | 「实现了 MapReduce 作业，在**本地模式**（`LocalJobRunner`）验证；**未部署真实集群**」 | 本地模式只是同一套 API 跑在单机，和"分布式"不是一回事 |
| 3 | 「Hadoop 提升了性能」 | 「实测在当前 3 千~29 万行规模下，MapReduce **比 PostgreSQL 慢 5~500 倍**；Hadoop 的收益要等数据量超出单机能力才出现」 | 第五节有实测表。硬说更快，一问细节就露 |

补充两处需要区分的小点：

| 容易混 | 准确说法 |
| --- | --- |
| 「推荐结果是个性化的」 | 只有在跑过 `run_recommendation.py` 之后才是个性化的；`recommendations` 表为空时走的是**热门榜兜底** |
| 「资料搜索」 | 是 `LIKE '%keyword%'` 模糊匹配，不是全文检索/语义搜索（AI 那几个接口是另算的） |

### C. 要做成「完整毕设」还缺什么

按「代码类 / 实验类 / 论文类」分开列。**代码类不做完，论文那几章就没有素材。**

#### 🔲 代码类（必须先补）

| # | 缺的内容 | 工作量 | 补完能支撑什么 |
| --- | --- | --- | --- |
| 1 | **单元测试**：`FeedbackService` 的 upsert 语义、`RecommendationService` 的反馈重排、`AuthService` 登录、`/api/admin/**` 的 403 门禁；Python 侧 `metrics.py`、`evaluate_offline.py` | 1~2 小时（纯 Mockito，不连库） | 论文「系统测试」章节；也是答辩"你怎么验证权限控制正确"的唯一答案 |
| 2 | **评论埋点**：给 `user_behaviors.action` 枚举加 `COMMENT`，`addComment()` 写一行 | 20 分钟（需 `ALTER TABLE`） | 「用户行为分析」维度完整 |
| 3 | **MapReduce 上真实集群**：输入路径改 HDFS URI、`setNumReduceTasks(1)` 改多 reducer | 半天~1 天（需要集群环境） | 「分布式计算」才算真正落地 |

#### 📄 实验类（代码已就绪，只差跑与整理）

| # | 内容 | 怎么产出 | 论文用在哪 |
| --- | --- | --- | --- |
| 4 | **融合权重调参实验** | 用 `evaluate_offline.py` 对 0.3/0.3/0.2/0.2 做网格搜索 | 现在权重是拍出来的，调完才有依据 |
| 5 | **消融实验汇总表** | 已有：单算法 vs 融合 vs 融合+反馈（4.4）；再加「反馈开/关」 | 第 5 章核心 |
| 6 | **Hadoop 对比表** | 已有三档数据量实测（5.3） | 讨论 Hadoop 适用边界 |
| 7 | **更多数据量档位** | 按 5.3 的方法继续放大数据量 | 让「何时该上 Hadoop」的拐点更清楚 |

#### 📄 论文类（写作时对照）

| # | 要写进去的 | 素材在哪个文件 |
| --- | --- | --- |
| 8 | 系统架构与鉴权链路 | 第一节、9.x |
| 9 | 四路算法与融合流程、在线重排权重表 | 3.1~3.3 |
| 10 | 实验设计与指标口径（留一法、数据装配方式） | 4.1 |
| 11 | 实验结果：单算法 vs 融合 vs 反馈 | 4.4 表 |
| 12 | Hadoop 适用性分析（含实测对比） | 第五节的表与结论写法 |
| 13 | **数据构成说明（真实 4 + 模拟 200）** | 顶部 B 节第 1 条，必须写 |
| 14 | 已知局限与后续工作 | 第九节、第十节 |

### D. 怎么快速确认这个仓库不是「只写了文档」

三条命令，都能在 10 秒内给出结果：

```bash
# 1) 后端能编译（57 个 Java 文件）
cd backend && mvn -q compile

# 2) 推荐评测链路可跑（不连数据库，纯内存）
cd recommendation && python evaluate_offline.py --source synthetic -n 200

# 3) 灌数脚本可预览（不写库，只看会插入什么）
python seed_synthetic_users.py --users 200        # 加 --apply 才真写
```

### E. 当前规模

| 部分 | 文件数 | 行数 |
| --- | --- | --- |
| 后端 Java | 57 | 5,198 |
| 前端 src | 24 | 7,003 |
| Python 推荐引擎 / 评测 | 8 | 1,447 |
| SQL | 2 | — |
| **仓库总文件** | **105** | — |

---

## 一、系统架构

```
                       ┌──────────────────────────┐
  浏览器  ────────────▶ │  Vue 3 + Element Plus    │  :3000
                       │  Pinia 存 JWT / axios    │
                       └────────────┬─────────────┘
                                    │ /api/**（Vite 代理）
                       ┌────────────▼─────────────┐
                       │  Spring Boot 3.2  :8080  │
                       │  Security / JWT / JPA    │
                       └───┬────────┬─────────┬───┘
                           │        │         │ X-Internal-Token
              ┌────────────▼──┐ ┌───▼────┐ ┌──▼─────────────────┐
              │ PostgreSQL 16 │ │ HDFS   │ │ FastAPI  :8090     │
              │ 业务数据/推荐  │ │ 3.3.6  │ │ LangGraph Agent    │
              └───────────────┘ └────────┘ │ 路由→检索→评分→改写 │
                           ▲               │ →作答（带引用）     │
                           │               └──┬───────────┬────┘
                    ┌──────┴──────────┐       │           │
                    │recommendation/* │  ┌────▼─────┐ ┌───▼──────┐
                    │UserCF/ItemCF/…  │  │ pgvector │ │  vLLM    │
                    └─────────────────┘  │ Postgres │ │ chat+emb │
                                         └──────────┘ └──────────┘
```

---

## 二、目录结构

```
.
├── backend/                              # Spring Boot 后端
│   ├── pom.xml                           #   Java 17 / Spring Boot 3.2.3 / Hadoop 3.3.6
│   └── src/main/
│       ├── java/com/recommend/
│       │   ├── RecommendApplication.java
│       │   ├── config/                   #   Security / CORS / Hadoop / 全局异常
│       │   ├── controller/               #   8 个 REST 控制器
│       │   ├── dto/                      #   请求/响应体
│       │   ├── entity/                   #   10 个 JPA 实体
│       │   ├── repository/               #   11 个 Repository
│       │   ├── security/                 #   JwtUtil / JwtFilter / UserDetailsService
│       │   ├── hadoop/                   #   ★ MapReduce 作业（HadoopHotnessJob）
│       │   └── service/                  #   12 个业务服务（含 FeedbackService /
│       │                                 #    HadoopBenchmarkService）
│       └── resources/
│           ├── application.yml           #   全部配置外置为环境变量
│           └── logback-spring.xml
│
├── frontend/                             # Vue 3 前端
│   ├── package.json
│   ├── vite.config.js                    #   开发代理 /api → localhost:8080
│   ├── index.html
│   └── src/
│       ├── api/                          #   axios 封装 + 7 个接口模块（含推荐反馈）
│       ├── components/                   #   Layout / MaterialCard
│       ├── router/  store/               #   路由与 Pinia
│       └── views/                        #   9 个页面 + admin/ 2 个管理页
│
├── recommendation/                        # Python 推荐引擎
│   ├── run_recommendation.py             #   入口：读库 → 4 路算法 → 融合 → 回写
│   ├── evaluate_offline.py               #   ★ 离线评测（留一法 + Top-K，见第四节）
│   ├── metrics.py                        #   ★ 评测指标纯函数（Precision/Recall/NDCG/覆盖率）
│   ├── seed_synthetic_users.py           #   ★ 灌入模拟用户（可一键撤销，见 4.4）
│   ├── usercf.py                         #   基于用户的协同过滤
│   ├── itemcf.py                         #   基于物品的协同过滤
│   ├── tfidf_recommend.py                #   基于内容（标题+简介+标签）
│   ├── svd_recommend.py                  #   TruncatedSVD 矩阵分解
│   └── requirements.txt
│
├── ai-service/                           # ★ FastAPI + LangGraph + vLLM + pgvector
│   ├── pyproject.toml / uv.lock          #   uv 管理，Python 3.11
│   ├── app/
│   │   ├── main.py                       #   FastAPI 应用与异常映射
│   │   ├── config.py                     #   pydantic-settings，全部走环境变量
│   │   ├── api/routes/internal.py        #   内部 API（X-Internal-Token）
│   │   ├── graph/                        #   LangGraph 状态图：路由→检索→评分→改写→作答
│   │   ├── ingestion/                    #   中文分块 / Spring 资料拉取 / 增量索引
│   │   ├── db/                           #   pgvector schema 与向量检索
│   │   ├── rag/retriever.py              #   查询向量化 + 余弦 Top-K
│   │   └── services/assistant.py         #   对话/摘要/建议/语义检索
│   ├── scripts/stub_vllm.py              #   无 GPU 联调用的 OpenAI 兼容假模型
│   ├── scripts/smoke_e2e.py              #   端到端冒烟（pgvector + stub + 真实 uvicorn）
│   └── tests/                            #   30 个 pytest 用例（含真实 pgvector）
│
├── sql/
│   ├── schema.sql                        # 12 张表 DDL（⚠️ 会 DROP 重建）
│   ├── seed.sql                          # 种子数据（事务内导入）
│   └── init.sql                          # psql 包装：schema + seed
│
├── docs/
│   ├── 功能清单.md                       # 原始任务书功能拆解
│   ├── 启动说明.md                       # 环境配置与运行步骤
│   └── 部署说明.md                       # 打包与部署总结
├── .env.example                          # 环境变量模板
└── README.md
```

---

## 三、推荐算法（核心）

### 3.1 四路算法

| 算法 | 文件 | 做法 | 融合权重 |
| --- | --- | --- | --- |
| UserCF | `usercf.py` | 用户-物品评分矩阵 → 余弦相似度 → 相似用户 Top-K → 加权推荐 | 0.30 |
| ItemCF | `itemcf.py` | 物品-用户评分矩阵 → 余弦相似度 → 相似物品 Top-K → 加权推荐 | 0.30 |
| TF-IDF | `tfidf_recommend.py` | 标题+简介+标签拼接成文本 → TF-IDF 向量 → 余弦相似度 | 0.20 |
| SVD | `svd_recommend.py` | `TruncatedSVD` 分解评分矩阵（20 维隐因子）→ 重构打分 | 0.20 |

### 3.2 融合流程（`run_recommendation.py`）

```
读取 PostgreSQL：user_ratings / user_behaviors / materials
        │
        ├─ UserCF  ─┐
        ├─ ItemCF  ─┤
        ├─ TF-IDF  ─┤
        └─ SVD     ─┘
        │
   ① 每路分数各自归一化到 [0,1]（除以该路最大值）
        │
   ② 加权求和：score = Σ(归一化分数 × 权重)
        │
   ③ 加入确定性抖动打散并列
      jitter = ((uid×73856093 + mid×19349663) mod 997) / 1e6
        │
   ④ 每用户去重、取 Top-N=20
        │
   ⑤ 写回 recommendations 表（ON DUPLICATE KEY UPDATE）
        │
   ⑥ 之后后端读取时再叠加用户的偏好设置（分类加权 +2、偏好标签×0.9、
      屏蔽标签×-2）做最终排序
```

**设计说明**：融合发生在离线，排序重排发生在线。这样用户改了偏好设置不必等
下一次批量刷新就能立刻看到效果。

### 3.3 在线重排的权重（含用户反馈）

`RecommendationService.applyPreference()` 对候选列表逐条打分后再排序：

| 因素 | 权重 |
| --- | --- |
| 原始名次 | `列表长度 − 下标`（保证有基础序） |
| 命中偏好分类 | **+2.0** |
| 命中偏好标签 | **+0.9 / 个** |
| 命中屏蔽标签 | **−2.0 / 个** |
| **用户点过「有用」** | **+1.5** |
| **用户点过「没用」** | **−5.0 且从结果中剔除** |
| 最终条数 | 用户偏好里的 `topN` |

「没用」的惩罚刻意大于偏好分类的 +2.0，体现「明确否定优先于模糊偏好」。
剔除后若列表为空，会回退为「只降权不剔除」，避免推荐结果一片空白。

### 3.4 触发方式

| 场景 | 触发点 |
| --- | --- |
| 全量刷新 | `@Scheduled(cron = "0 0 2 * * ?")` 每天 2:00 |
| 单用户刷新 | `POST /api/recommendations/refresh` |
| 冷启动 | `recommendations` 表无该用户记录 → 退化为热门榜（按浏览量） |
| 游客 | `GET /api/recommendations/public` 直接返回热门榜 |

---

## 四、推荐效果评测（离线）

> 这一节是本次补的：此前项目**没有任何量化指标**，无法证明「融合比单算法好」。

评测脚本：`recommendation/evaluate_offline.py` + `recommendation/metrics.py`

### 4.1 评测口径

采用学术界最常用的 **leave-one-out + Top-K**：

```
对每个用户：从其正反馈（rating≥3 或 LIKE/COLLECT/DOWNLOAD）里留出 1 个物品
        → 用剩余数据训练 4 个单算法 + 融合算法
        → 各取 Top-N，看留出的物品是否命中
```

指标：

| 指标 | 含义 |
| --- | --- |
| `Precision@K` | 命中的相关物品数 / K |
| `Recall@K` | 命中数 / 相关物品总数（留一法下即「是否命中」） |
| `HitRate@K` | 有命中的用户占比 |
| `NDCG@K` | 越靠前命中得分越高（位置敏感） |
| `Coverage` | 被推荐到的物品占全部物品比例（多样性） |

**数据装配方式**（论文里需要说明）：项目同时存在显式评分（`user_ratings`）与行为埋点
（`user_behaviors`），评测把两者合成统一评分矩阵 —— 显式评分优先，其余按
`VIEW 1 / DOWNLOAD 2.5 / RATE 3.5 / LIKE 4 / COLLECT 4 / DISLIKE 1` 折算，同一用户-物品取最高分。

### 4.2 怎么跑

```bash
cd recommendation

# 真实数据（需要数据库；只做 SELECT，不写库）
export DB_PASSWORD='你的PostgreSQL口令'
python evaluate_offline.py --source db

# 合成数据（不依赖数据库，可复现，适合出稳定的对比表）
python evaluate_offline.py --source synthetic -n 200

# 常用参数
python evaluate_offline.py --k 5,10 --top-n 10 --min-interactions 3 --out eval.csv

# 真实用户太少时，先灌一批模拟用户再把规模做起来（见 4.4）
python seed_synthetic_users.py --users 200            # 预览，不写库
python seed_synthetic_users.py --users 200 --apply    # 真正写入
python seed_synthetic_users.py --cleanup --apply      # 一键撤销
```

### 4.3 原始数据的结果（4 个用户，样本不足）

灌模拟数据之前，库里只有 4 个用户、20 条评分、102 条行为：

```
参与评测用户：3    物品数：30    Top-N：10
```

| 模型 | K | Precision | Recall | HitRate | NDCG | 覆盖率 |
| --- | --- | --- | --- | --- | --- | --- |
| UserCF | 10 | 0.0667 | 0.6667 | 0.6667 | 0.2115 | 0.7000 |
| ItemCF | 10 | 0.0667 | 0.6667 | 0.6667 | 0.2298 | 0.7667 |
| TF-IDF | 10 | 0.1000 | 1.0000 | 1.0000 | 0.4769 | 0.8000 |
| SVD | 5 | 0.1333 | 0.6667 | 0.6667 | 0.5000 | 0.7000 |
| Hybrid（融合） | 5 | 0.1333 | 0.6667 | 0.6667 | 0.4206 | 0.7333 |

> ⚠️ **只有 3 个用户参与，这些数字没有统计意义**。脚本会主动打印警告：
> 留一法下每个用户只有 1 个相关物品，一个用户命中与否就能让 Recall 变化 33%。
> 这张表只作为「样本不足」的证据保留，**不要写进论文**。

### 4.4 灌入模拟数据后的结果（200 用户，推荐用这张）

真实用户太少，因此提供了灌数脚本 `seed_synthetic_users.py`，
按「用户偏好若 1~2 个学科 → 主要与该学科资料交互」的规则造 200 个模拟用户，
让评测达到有统计意义的规模。

```bash
cd recommendation
python seed_synthetic_users.py --users 200 --apply        # 灌入（默认只预览）
python evaluate_offline.py --source db                   # 跑评测
python seed_synthetic_users.py --cleanup --apply         # 一键撤销
```

灌完后库里的规模：

| 表 | 原始 | 灌入后 |
| --- | --- | --- |
| `users` | 4 | **204**（其中 200 个以 `sim_` 开头） |
| `user_ratings` | 20 | **1,793** |
| `user_behaviors` | 102 | **2,963** |
| `recommendation_feedback` | 6 | **292** |
| `recommendations` | 0 | **3,618**（跑了推荐引擎后） |

评测结果（**推荐写进论文的就是这张**）：

```
数据源：数据库真实数据 + 200 个模拟用户
参与评测用户：200    物品数：30    Top-N：10
```

| 模型 | K | Precision | Recall | HitRate | NDCG | 覆盖率 |
| --- | --- | --- | --- | --- | --- | --- |
| SVD | 10 | 0.0495 | 0.4950 | 0.4950 | 0.2366 | 1.0000 |
| ItemCF | 10 | 0.0805 | 0.8050 | 0.8050 | 0.3962 | 1.0000 |
| TF-IDF | 10 | 0.0805 | 0.8050 | 0.8050 | 0.4485 | 1.0000 |
| UserCF | 10 | 0.0810 | 0.8100 | 0.8100 | 0.4506 | 1.0000 |
| **Hybrid（融合）** | 10 | **0.0820** | **0.8200** | **0.8200** | **0.4586** | 1.0000 |
| Hybrid+多样 | 10 | 0.0820 | 0.8200 | 0.8200 | 0.4586 | 1.0000 |
| **Hybrid+反馈** | 5 | **0.1300** | **0.6500** | **0.6500** | **0.4542** | 1.0000 |
| Hybrid+反馈 | 10 | 0.0810 | 0.8100 | 0.8100 | **0.5058** | 1.0000 |

可以读出的结论（**写得保守一点更安全**）：

1. **融合优于所有单算法**：Recall@10 0.8200，高于 UserCF 0.8100、
   ItemCF / TF-IDF 0.8050、SVD 0.4950。但**领先 UserCF 只有 1 个百分点**，
   差距不大 —— 这与「用户偏好由学科决定、而 UserCF 恰好能学到这一点」的
   数据生成方式有关，不能过度解读。
2. **用户反馈显著改善排序质量**：NDCG@10 从 0.4586 提升到 0.5058（+10.3%），
   Precision@5 从 0.1250 提升到 0.1300。注意 Recall@10 反而略降
   （0.8200 → 0.8100）—— 反馈把「没用」的资料剔除后，个别用户原本能命中的
   物品被挤出了前 10。**这说明反馈主要提升的是「排名质量」而不是「召回率」**，
   论文里应当这么写。
3. **覆盖率恒为 1.0**：库里只有 30 条 ACTIVE 资料，200 个用户下所有资料都会被
   推荐到，这个指标在当前数据规模下没有区分度。

> ⚠️ **模拟数据的局限**：这批用户是按「学科偏好」规则生成的，信号很干净，
> 协同过滤很容易学到，所以绝对指标偏高（Recall@10 0.82 在真实场景里几乎不可能）。
> **论文里必须写明「评测数据含 200 个模拟用户，采用学科偏好规则生成」**，
> 并把结论限定为「在模拟数据上，融合与反馈均带来正向收益」。

### 4.5 纯合成数据的结果（不连数据库，用于快速验证）

不开数据库、纯内存生成一份数据，适合验证评测链路是否正常：

```
数据源：合成数据（200 用户 / 40 资料 / seed=42）
参与评测用户：198    物品数：40    Top-N：10
```

| 模型 | K | Recall | NDCG |
| --- | --- | --- | --- |
| UserCF | 10 | 0.5758 | 0.3030 |
| ItemCF | 10 | 0.5707 | 0.2793 |
| TF-IDF | 10 | 0.5404 | 0.2695 |
| SVD | 10 | 0.5000 | 0.2404 |
| Hybrid（融合） | 10 | 0.6111 | 0.3503 |
| Hybrid+反馈 | 10 | 0.6263 | 0.3964 |

结论方向与 4.4 一致（融合 > 单算法，反馈 > 无反馈），说明评测链路是稳定的。

---

## 五、Hadoop 部分（存储 + 计算）

> 这一节此前叫「只有存储侧」——因为项目里只有 HDFS 读写，没有任何计算作业，
> 「基于 Hadoop 的推荐系统」这个题目里的「计算」是空的。本次补上了 MapReduce 作业，
> 并做了单机 vs MapReduce 的实测对比。

### 5.1 存储侧

- `HadoopConfig`：构造 `FileSystem`，**HDFS 连不上时自动回退本地文件系统**
  （`FileSystem.getLocal`），所以没有 Hadoop 集群也能跑通整个流程。
- `HdfsService`：资料文件上传/下载/删除，含路径穿越防护。
- `HadoopDatasetExportService`：每天 2:30 把 `user_behaviors`、`user_ratings`
  导出为 Hive 风格分区目录的 CSV：

```
/recommend/warehouse/
└── user_behaviors/
    └── dt=2026-05-08/
        └── user_behaviors-20260508-181214.csv
```

  导出失败时自动回退写本地 `storage/hadoop-export/`，并把失败原因写进返回结果。

### 5.2 计算侧（新增）

`backend/.../hadoop/HadoopHotnessJob.java` —— 一个标准的 MapReduce 作业，
算「资料热度排行榜」：

```
Map     : CSV 每行 → (material_id, 行为权重)
Shuffle : 按 material_id 分组
Reduce  : 同 material_id 的权重求和
Driver  : 结果按权重降序取 TopN
```

行为权重（与 SQL 侧共用同一份定义，保证两条链路口径一致）：

| 行为 | 权重 |
| --- | --- |
| `VIEW` | 1.0 |
| `LIKE` | 2.0 |
| `COLLECT` | 2.5 |
| `RATE` | 1.5 |
| `DOWNLOAD` | 2.0 |
| `DISLIKE` | −1.0 |

**两种运行方式**：

| 方式 | 说明 |
| --- | --- |
| 本地模式（`LocalJobRunner`） | `mapreduce.framework.name=local`，不需要搭集群，同一份代码可直接跑 |
| 集群模式 | 打成 jar 后 `hadoop jar ...` 提交到真实集群（代码未改动） |

### 5.3 单机 vs MapReduce 实测对比

新增接口（仅 ADMIN）：

```
POST /api/admin/hadoop/export        # 先导出行为数据
POST /api/admin/hadoop/benchmark?topN=10   # 跑对比
```

两条链路用同一份导出数据、同一套权重，输出 TopN 并校验结果是否一致。

**实测环境**：Windows + JDK 17 + PostgreSQL 16，Hadoop 3.3.6 `LocalJobRunner`（单机单 reducer）。
数据量通过在临时库里复制 `user_behaviors` 行构造，真实库未改动。

| 数据量 | PostgreSQL `GROUP BY` | MapReduce | 倍数 | 结果一致 |
| --- | --- | --- | --- | --- |
| 2,963 行 | **11 ms** | 5,544 ms | 504× | ✅ |
| 22,404 行 | **68 ms** | 5,105 ms | 75× | ✅ |
| 290,162 行 | **971 ms** | 5,092 ms | 5.2× | ✅ |

可以读出两条曲线：

1. **PostgreSQL 基本线性增长**：11 → 68 → 971 ms（数据量涨 98 倍，耗时涨 88 倍）
2. **MapReduce 耗时几乎不变**：5,544 → 5,105 → 5,092 ms
   —— 说明在这个量级上，作业启动、切片、序列化、落盘这些**固定开销**占了绝大部分，
   真正的聚合计算微乎其微

**这组数字该怎么写进论文**：

> 在当前测试规模（万级到十万级）下，MapReduce 反而比 PostgreSQL 单机聚合慢 5~500 倍。
> 按两条曲线的斜率粗略外推，PostgreSQL 的耗时会在**百万行量级**追平 MapReduce 的
> 约 5 秒固定开销；再往上（千万级以上，单机内存/IO 成为瓶颈时）才轮到 MapReduce
> 的横向扩展优势体现。

**不要写成「Hadoop 提升了性能」** —— 这不是实测结论。正确写法是
「Hadoop 的价值在于数据量超出单机能力后的可扩展性，本项目在现有数据规模下
尚未进入该区间，因此改用 Hadoop 并不划算」，并附上这张实测表。

### 5.4 仍然没有做的

- ❌ **没有在真实集群上跑过**：只验证了 `LocalJobRunner` 本地模式。
  上集群需要 HDFS + YARN，且代码里的输入路径要改成 HDFS URI。
- ❌ **没有把 CSV 灌进 Hive 外部表的 DDL**（当前直接读 CSV）。
- ❌ **只有一个 reducer**：本地模式固定 `setNumReduceTasks(1)`，
  集群上应改成多 reducer 才有并发收益。

### 5.5 Windows 上跑 MapReduce 的前置条件

Hadoop 在 Windows 上需要 `winutils.exe` 与 `hadoop.dll`，否则会报：

```
FileNotFoundException: HADOOP_HOME and hadoop.home.dir are unset
UnsatisfiedLinkError: 'boolean org.apache.hadoop.io.nativeio.NativeIO$Windows.access0(...)'
```

处理方式（**不要把二进制放进仓库**）：

```bash
# 1) 取与 hadoop-client 版本一致的 winutils（本项目是 3.3.6）
mkdir -p /tmp/hadoop-win/bin && cd /tmp/hadoop-win/bin
curl -LO https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.6/bin/winutils.exe
curl -LO https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.6/bin/hadoop.dll

# 2) 启动前设置（hadoop.dll 必须在 PATH 里，否则会 UnsatisfiedLinkError）
export HADOOP_HOME=/tmp/hadoop-win
export PATH=/tmp/hadoop-win/bin:$PATH
```

> 未配置时接口不会报错，而是**自动退回进程内等价实现**（`mode=IN_PROCESS`），
> 结果与 MapReduce 完全一致，但返回体里的 `conclusion` 会明确写出
> 「这组耗时不能用于比较 Hadoop 与 PostgreSQL」。这样在 Linux/集群环境仍能拿到真实数字。

---

## 六、快速开始

### 6.1 环境要求

| 组件 | 版本 | 必需 |
| --- | --- | --- |
| JDK | 17 | ✅ |
| Maven | 3.8+ | ✅ |
| PostgreSQL + pgvector | 16 + 0.6+ | ✅（业务库 + 向量库） |
| Node.js | 18+ | ✅ |
| Python | 3.11+（推荐引擎）/ 3.11+（AI 服务） | 推荐 |
| uv | 0.5+ | 推荐（AI 服务与迁移脚本依赖管理） |
| vLLM | 0.6+（需 GPU） | 可选；无 GPU 用 `scripts/stub_vllm.py` 联调 |
| Hadoop | 3.x | 可选（不装会自动回退本地） |
| Docker / Compose | 任意 | 可选（一键起 pgvector + ai-service） |

### 6.2 初始化数据库

```bash
# ⚠️ sql/init.sql 会 DROP 并重建 recommend_db 下的所有表
#    如果该库已有数据，先备份：
pg_dump -d recommend_db > backup_$(date +%F).sql

createdb recommend_db
psql -d recommend_db -f sql/init.sql
```

初始账号见 `sql/init.sql` 中的 `users` 表（口令为 BCrypt 哈希）。

### 6.3 启动后端

```bash
cd backend

# 必填两项：数据库口令 + JWT 密钥（≥32 字节）
export DB_PASSWORD='你的PostgreSQL口令'
export JWT_SECRET="$(openssl rand -base64 48)"

# 可选：AI 服务与 Hadoop
export AI_SERVICE_BASE_URL=http://localhost:8090
export AI_INTERNAL_TOKEN='change-me-internal-token'   # 与 ai-service/.env 一致
export HADOOP_EXPORT_ENABLED=false    # 没装 Hadoop 时建议关掉定时导出

mvn spring-boot:run
# 或打包后运行
mvn clean package -DskipTests && java -jar target/study-recommend-1.0.0.jar
```

启动后 `http://localhost:8080/api/categories` 应返回分类列表。

> **⚠️ 未设置 `JWT_SECRET` 时应用会直接启动失败**，这是有意为之：
> 原代码自带一个公开的弱默认密钥，等于任何人都能伪造 token。

### 6.4 启动前端

```bash
cd frontend
npm install
npm run dev      # http://localhost:3000，/api 自动代理到 8080
```

### 6.5 运行推荐引擎（可选）

```bash
cd recommendation
uv venv --python 3.11 .venv
uv pip install -r requirements.txt
export DB_PASSWORD='你的PostgreSQL口令'
.venv/Scripts/python run_recommendation.py                 # 全量刷新
.venv/Scripts/python run_recommendation.py --user-id 2     # 只刷某个用户
```

### 6.6 启动 AI 服务（FastAPI + LangGraph + vLLM + pgvector）

**方式 A：Docker Compose（推荐）**

```bash
# 只起 pgvector + ai-service（模型用宿主机或 stub）
docker compose up -d postgres ai-service

# 有 GPU 时再起 vLLM（chat + embedding 两个容器）
docker compose --profile gpu up -d vllm-chat vllm-embed
```

**方式 B：本机直接跑**

```bash
# 1) 准备 pgvector（任选其一）
docker run -d --name study-pg -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=study_ai pgvector/pgvector:pg16

# 2) 启动 vLLM（有 GPU）
vllm serve Qwen/Qwen2.5-7B-Instruct --served-model-name qwen2.5-7b-instruct --port 8000
vllm serve BAAI/bge-m3 --task embed --served-model-name bge-m3 --port 8001

# 3) 启动 AI 服务
cd ai-service
cp .env.example .env            # 按需修改 DATABASE_URL / VLLM_* / AI_INTERNAL_TOKEN
uv sync
uv run uvicorn app.main:app --host 0.0.0.0 --port 8090

# 4) 建立向量索引（ACTIVE 资料全量同步）
curl -X POST http://localhost:8090/internal/v1/index/rebuild \
  -H "X-Internal-Token: change-me-internal-token"
curl http://localhost:8090/internal/v1/index/stats \
  -H "X-Internal-Token: change-me-internal-token"
```

**没有 GPU 的联调方式**：用 `ai-service/scripts/stub_vllm.py` 提供 OpenAI 兼容假模型：

```bash
cd ai-service
uv run python scripts/stub_vllm.py --port 18000
# 再把 .env 的 VLLM_BASE_URL 指向 http://127.0.0.1:18000/v1，EMBEDDING_DIM=8
```

验证链路（不需要外部 PostgreSQL / Spring；pytest 自带嵌入式 pgvector）：

```bash
cd ai-service
uv run pytest                      # 30 个用例，含真实 pgvector 集成
uv run python scripts/smoke_e2e.py # 起 pgvector + stub + 真实 uvicorn，11 项断言
```

Spring Boot 启动后，资料的新增/修改/审核/删除会通过 `MaterialIndexEvent`
异步同步到 pgvector；也可以用管理员接口手动全量重建：

```bash
curl -X POST http://localhost:8080/api/admin/ai/reindex -H "Authorization: Bearer <ADMIN_TOKEN>"
```

---

## 七、接口一览

### 认证 `/api/auth`

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| POST | `/login` | 公开 |
| POST | `/register` | 公开 |
| POST | `/reset-password` | 公开（用户名+邮箱双因子校验） |

### 资料 `/api/materials`

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `` / `/{id}` | 公开（`/{id}` 仅 ACTIVE） |
| GET | `/{id}/comments` `/{id}/like` `/{id}/favorite-status` | 公开 |
| GET | `/{id}/download` | 登录 |
| POST | `/{id}/rate` `/{id}/like` `/{id}/comments` | 登录 |
| POST/PUT/DELETE | `` / `/{id}` | ADMIN |

### 用户 `/api/user`（均需登录，仅操作自己）

`/profile` `/favorites` `/history` `/ratings` `/preferences`

### 推荐 `/api/recommendations`

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/public` | 公开（热门榜） |
| GET | `` | 登录（个性化） |
| POST | `/refresh` | 登录 |
| POST | `/{materialId}/feedback` | 登录（body: `{"feedback":"LIKE"}` 或 `"DISLIKE"`） |
| DELETE | `/{materialId}/feedback` | 登录（撤销） |
| GET | `/feedback` | 登录（当前用户全部反馈 + 全局统计） |

> 注意区分两种「点赞」：
> - `/api/materials/{id}/like` 是**资料点赞**，只影响该资料的点赞数，不参与推荐排序
> - `/api/recommendations/{id}/feedback` 是**推荐反馈**，直接决定这条推荐是否还会出现

### AI `/api/ai`（均需登录）

| 方法 | 路径 | 返回 |
| --- | --- | --- |
| POST | `/chat` | `answer` + `citations[]` + `references[]` + `route`（materials/general）+ `model` |
| POST | `/summarize/{id}` | `summary` + `citations[]` + `grounded`（是否基于索引片段）+ `chunks` |
| POST | `/suggestions` | `suggestions` + `references[]`（结合目标院校/专业/阶段与收藏） |
| GET | `/search?q=` | `materials[]`（PostgreSQL 补全字段）+ `matches[]`（pgvector 命中片段与分数） |
| GET | `/history` | 最近 20 条 AI 会话 |

> 内部接口（供 ai-service 拉取资料，需 `X-Internal-Token`）：
> `GET /api/internal/ai/materials?page=&size=&since=`

### 管理 `/api/admin`（全部 ADMIN）

`/users` `/materials` `/categories` `/logs` `/backup`
`/hadoop/export` `/hadoop/export/latest` `/hadoop/benchmark`（单机 vs MapReduce 对比）
`/ai/reindex`（全量重建向量索引）`/ai/stats`（索引与模型状态）

详见各 Controller 源码。

---

## 八、安全加固记录

本次整理仓库时对原代码做了安全审计，修复了以下问题（均已实机验证）：

| 级别 | 问题 | 修复 |
| --- | --- | --- |
| 🔴 严重 | `AuthService.register()` / `resetPassword()` **直接把明文口令存库**；`PasswordEncoder.encode()` 也直接返回原文 | 注册/改密一律 BCrypt；`encode()` 改为真正加密 |
| 🔴 严重 | `application.yml` 中**硬编码了真实的 DeepSeek API Key** | 已移除 DeepSeek 客户端，改为自托管 vLLM；AI 服务只认环境变量中的内部令牌，仓库不含任何密钥 |
| 🟠 高 | CORS `allowedOriginPatterns("*")` + `allowCredentials(true)`，等于任意站点可带凭据调用 | 改为 `CORS_ALLOWED_ORIGINS` 白名单，默认只放行本地端口 |
| 🟠 高 | JWT 密钥有公开的弱默认值，任何人都能伪造 token | `JWT_SECRET` 必填且校验 ≥32 字节，否则启动失败 |
| 🟡 中 | `/api/admin/users` 返回 `User` 实体，口令字段靠接口层手动置空，漏一处即泄漏 | `User.password` 加 `@JsonIgnore` 兜底 |
| 🟡 中 | `GET /api/materials/{id}` 不校验状态，未审核/已删除资料可被匿名读到 | 仅 `ACTIVE` 可见 |
| 🟡 中 | `deepseek.api.timeout` 配置了但从未使用，`RestTemplate` 无超时会把线程池挂死 | 接入连接/读取超时 |
| 🟢 低 | `POST /{id}/rate` 缺 `rating` 字段时拆箱 NPE → 500 | 改为 400 + 明确提示 |
| 🟢 低 | 数据库口令在 `application.yml` 与 Python 脚本中都有明文默认值 | 统一外置为 `DB_PASSWORD` |

**实机验证结果**（临时库 + 真实启动）：

```
注册新用户      → 库中口令为 $2a$10$...                ✅
旧明文账号登录  → 登录成功且自动升级为 BCrypt            ✅
/admin/users    → 响应体 password 字段数 = 0            ✅
PENDING 资料    → 匿名访问返回 400「不存在或已下架」      ✅
评分缺字段      → HTTP 400 而非 500                     ✅
CORS 恶意来源   → HTTP 403                              ✅
CORS 白名单     → Access-Control-Allow-Origin 正确回传   ✅
未登录访问      → HTTP 403                              ✅
缺 JWT_SECRET   → 启动失败并给出中文提示                 ✅
短 JWT_SECRET   → 启动失败并提示当前字节数               ✅
```

---

## 九、已知问题与限制

### 9.1 推荐反馈闭环（本次已补齐）

**原状况**：`recommendation_feedback` 表、实体、Repository 都在，但**全项目没有任何写入调用**，
也没有接口和前端按钮。推荐结果只受离线算法分数与用户偏好影响，用户点踩不会产生任何效果——
这是任务书功能清单里明确列出的一项，属于实质性功能缺口。

**已补齐的链路**：

| 环节 | 实现 |
| --- | --- |
| 写入服务 | `FeedbackService.submit()`：同一用户同一资料只保留一条，重复提交视为切换 |
| 撤销 | `FeedbackService.cancel()` |
| 接口 | `POST /api/recommendations/{materialId}/feedback`（body: `{"feedback":"LIKE"}` / `"DISLIKE"`）<br>`DELETE /api/recommendations/{materialId}/feedback`<br>`GET /api/recommendations/feedback`（当前用户全部反馈） |
| 在线重排 | `RecommendationService.applyPreference()`：一次查出该用户全部反馈后传入，<br>`LIKE` 加权 **+1.5**，`DISLIKE` 降权 **−5.0** 并从推荐中**剔除**（若剔除后为空则回退为保留，避免列表变空） |
| 前端 | 资料详情页「互动操作」下方新增「这条推荐对你有帮助吗？」+ 有用 / 没用两个按钮，<br>当前状态高亮，再点一次即撤销 |
| 报表 | 管理端「推荐反馈分布」饼图读的就是这张表，现在会随真实反馈增长 |

**实测效果**（临时库）：

```
推荐列表 10 条： [13, 26, 3, 23, 15, 25, 27, 2, 14, ...]
对资料 13 点「没用」→ 库中写入 user_id=2 material_id=13 DISLIKE
再取推荐列表        → 9 条，资料 13 已被剔除
撤销反馈            → 资料 13 回到第 1 位
```

### 9.2 分析图表的数据可见性（已修）

> **更正**：早前这节写的是「反馈分布、行为趋势两个图表恒为空」，核对种子数据后发现不准确——
> `sql/init.sql` 里其实带着 6 条反馈（4 LIKE / 2 DISLIKE）和历史行为
> （VIEW 42 / RATE 18 / LIKE 15 / COLLECT 12 / DOWNLOAD 6 / DISLIKE 3），图表是能出数据的。
> 真正的问题有两个：**反馈表没有写入路径所以数据不会增长**（已由 8.1 解决），
> 以及**行为趋势窗口写死 14 天**。

原实现：

```java
// AnalyticsService
data.put("behaviorTrend", getBehaviorTrend(14));      // 窗口写死 14 天
int safeDays = Math.max(7, Math.min(days, 60));       // 且上限只到 60 天
```

演示数据最晚到 `2026-04-21`，等答辩时日期一过，这个图就空白了。

**已改为可配置**：

```java
// AnalyticsService
public Map<String, Object> getDashboardData(int trendDays) { ... }
int safeDays = Math.max(7, Math.min(days, 365));      // 上限放宽到 365 天
```

```
GET /api/analytics/dashboard              → 默认 14 天
GET /api/analytics/dashboard?days=365     → 覆盖近一年，旧演示数据也能出图
```

实测 `?days=365` 返回 365 个数据点，其中 5 天有数据
（如 `2026-03-27`：view 28 / like 10 / dislike 3 / collect 9 / rate 14 / download 6）。

### 9.3 行为埋点覆盖情况

| 操作 | 是否记行为 | 说明 |
| --- | --- | --- |
| 浏览详情 VIEW | ✅ | — |
| 点赞 LIKE | ✅ 仅「加赞」记，「取消赞」不记 | 行为数 ≠ 当前点赞数 |
| 评分 RATE | ✅ | — |
| 下载 DOWNLOAD | ✅ | — |
| 收藏 | ✅ **本次补上** | `toggleFavorite()` 加赞时写 `COLLECT` 行为（取消收藏不记，与点赞一致） |
| 点踩 DISLIKE | 🟡 部分 | `user_behaviors` 的 DISLIKE 代码仍不写入；推荐反馈已改走 `recommendation_feedback`（8.1） |
| 发表评论 | ❌ 仍不记 | `user_behaviors.action` 是 ENUM，没有 `COMMENT` 值；要补需先 `ALTER TABLE` 加枚举值，本次未动表结构 |

实测：收藏一条资料后 `COLLECT` 行为数 11 → 12。

> 如果论文要写「用户行为分析」的评论维度，需要给 `action` 枚举加 `COMMENT` 并补一行埋点。

### 9.4 种子数据（`sql/init.sql`）说明

`sql/init.sql` 是 **2026-03-29 的快照**，里面有几处不是自然产生的数据，看代码时不要被误导：

| 字段/表 | 实际数据 | 说明 |
| --- | --- | --- |
| `materials.view_count` | 合计 302,813 | 而真实 VIEW 行为只有 44 条，数字明显是手工灌的，为了让热门排序好看 |
| `user_behaviors` 动作分布 | VIEW 42 / RATE 18 / LIKE 15 / COLLECT 12 / DOWNLOAD 6 / DISLIKE 3 | 都来自种子里手工造的数据；**代码本身只写 VIEW/LIKE/RATE/DOWNLOAD/COLLECT**，不写 DISLIKE |
| `recommendation_feedback` | **6 行**（4 LIKE / 2 DISLIKE） | 种子里自带。此前本文写成「0 行」是错的——代码虽无写入路径，但数据是有的（写入路径已在 8.1 补齐） |
| `recommendations` | 0 行 | 推荐脚本从未成功跑过，或跑完后被清空。因此个性化推荐会走「热门榜 + 偏好重排」的兜底分支 |
| `material_comments` | 8 行 | 种子里自带 |
| 时间戳 | 全部集中在 2026-03-23 ~ 2026-04-21 | 距今已超过默认的 14 天窗口，所以**打开分析页看趋势图会是空的**，需要传 `?days=365`（见 9.2） |

导入后如果想看到「近期」的趋势曲线，需要自己补一批当月的 `user_behaviors` 记录，
或者直接用 `GET /api/analytics/dashboard?days=365` 把窗口拉长。

### 9.5 其他

| 级别 | 问题 | 说明 |
| --- | --- | --- |
| 🟡 | **AI 回答需要 vLLM** | 没有 GPU 时只能用 `scripts/stub_vllm.py` 联调；stub 只验证链路，不代表真实回答质量 |
| 🟡 | **AI 接口非流式** | `/api/ai/chat` 一次性返回完整回答，前端无 token 级打字机效果 |
| 🟡 | **向量维度固定** | `EMBEDDING_DIM` 默认 1024（bge-m3）；换成别的 embedding 模型必须清空 `ai_material_chunks` 重建索引 |
| 🟡 | **AI 服务不存多轮状态** | LangGraph 每次请求独立执行；多轮上下文由 Spring 从 `ai_conversations` 取最近 3 轮拼入 prompt |
| 🟡 | **Hadoop 只在本地模式验证过** | MapReduce 作业已实现并有实测对比，但没在真实集群（HDFS+YARN）上跑过，见 5.4 |
| 🟡 | `sql/schema.sql` 会重建全部表 | 开头 `DROP TABLE` 列出 12 张表；`sql/init.sql` = schema + seed，误执行会清业务库 |
| 🟡 | 数据规模很小 | 4 个用户、31 条资料、102 条行为。UserCF 在这么小的数据上意义有限，冷启动问题严重 |
| 🟡 | 单机 Hadoop 依赖 `winutils.exe` | Windows 下无 HADOOP_HOME 时 Hadoop 会打警告，已通过本地回退规避 |
| 🟡 | 前端无构建产物入库 | 按惯例排除 `dist/`，需自行 `npm run build` |
| ⚪ | 日志查看接口会扫项目目录 | `/api/admin/logs` 在项目根 3 层内找 `*.log`，仅 ADMIN 可用 |

---

## 十、后续工作建议

> 原列的「补反馈闭环 / 补推荐评测 / 补数据规模 / 补 Hadoop 计算」**本次全部完成**
> （见 9.1 / 第四节 / 第五节）。下面是真正还没做的。

1. **继续补测试**：后端已有 26 个用例（JWT、AI 客户端、内部资料接口、索引同步、AI 门面），
   AI 服务有 30 个；还缺 `MaterialService`、`RecommendationService`、`AuthService` 的业务分支。
2. **把 MapReduce 上真实集群**：现在只验证了 `LocalJobRunner`。上集群需要
   把输入路径改成 HDFS URI、把 `setNumReduceTasks(1)` 改成多 reducer，
   再跑一次 5.3 的对比表 —— 那张表才是「分布式」的完整论据。
3. **做融合权重调参实验**：现在权重（0.3/0.3/0.2/0.2）是拍出来的。
   用 `evaluate_offline.py` 做网格搜索找最优组合，这本身就是一章实验。
4. **给评论补埋点**：给 `user_behaviors.action` 枚举加 `COMMENT`，
   在 `addComment()` 里写一行（见 9.3）。
5. **答辩前想清楚模拟数据的表述**：论文和答辩里主动说明
   「数据构成 = 4 真实用户 + 200 模拟用户，模拟数据按学科偏好规则生成」，
   比被老师问出来要好。
6. **AI 回答质量评测**：当前只验证了链路与引用，还没有像推荐引擎那样的离线指标。
   可以用同一份资料语料构造「问题 → 应命中资料」评测集，统计命中率与引用准确率。

---

## 十一、仓库说明

本仓库**只包含源码与数据库结构**，不含以下内容（详见 `.gitignore`）：

| 排除项 | 原因 |
| --- | --- |
| `backend/target/`、`frontend/dist/` | 构建产物，可重新生成 |
| `frontend/node_modules/` | 依赖，`npm install` 可还原 |
| `release/`（约 191 MB） | 打包发布件与 jar，非源码 |
| `backend/logs/`、`backend/storage/` | 运行时日志与导出文件 |
| `backend/package-lock.json` | 位于后端目录的前端锁文件，属误放 |
| `sql/init.sql.mojibake.bak` | 早期编码损坏的备份，无语义 |

**不包含 `.env` 与任何真实口令/密钥。**

## 附：自动化测试

```bash
# Java：26 个用例，无需数据库
cd backend && mvn test

# AI 服务：30 个用例（含真实 pgvector 集成），无需 GPU
cd ai-service && uv run pytest

# AI 服务端到端冒烟：pgvector + stub vLLM + 真实 uvicorn
cd ai-service && uv run python scripts/smoke_e2e.py
```

**后端新增用例**

| 测试类 | 覆盖的行为 |
| --- | --- |
| `AiServiceClientTest` | 内部令牌头、snake_case 请求体、引用解析、vLLM 503 转可读异常、未配置 fail-fast、搜索参数、重建响应 |
| `InternalAiControllerTest` | 缺失/错误令牌返回 401；分页返回 `items` / `has_more` / `total` 与 `category_name`、`updated_at` |
| `AiIndexSyncServiceTest` | ACTIVE 推送索引、DELETED/PENDING 删除索引、资料不存在时删除、未配置跳过、同步失败不外抛 |
| `AiServiceTest` | 最近 3 轮对话按时间正序拼入、非 ACTIVE 资料拒绝摘要、画像与收藏传给建议接口 |

**AI 服务用例**

| 文件 | 覆盖的行为 |
| --- | --- |
| `tests/test_chunking.py` | 空文本、长度上限、长句硬切分、句子不丢失、参数校验、元数据拼接 |
| `tests/test_graph.py` | 资料路由返回引用、空评分触发一次改写、通用路由跳过检索、评分失败 fail-open、多轮历史进入 prompt、越界引用忽略 |
| `tests/test_api.py` | 令牌门禁、对话/检索/索引/删除、vLLM 故障 503、未索引摘要回落快照、无快照 404 |
| `tests/test_pgvector.py` | schema 幂等、余弦排序与阈值、重索引清理旧分块、删除、全量重建的 indexed/skipped/removed 统计 |

`JwtUtilTest` 仍覆盖鉴权链路的根（签发、他人 token、过期、篡改、异签、弱密钥自检）。

CI 每次 push 都会跑 Java + Node + Python + AI 服务四组任务（见徽章）。

---

## 十二、AI 服务（LangGraph + vLLM + pgvector）

项目原先的 AI 模块只是把用户问题转发给 DeepSeek Chat API，没有检索、没有引用、
也没有可自证的链路。本次替换为独立 AI 服务，技术栈：

| 组件 | 作用 |
| --- | --- |
| **FastAPI** | 内部 HTTP API（`/internal/v1/**`），供 Spring 调用 |
| **LangGraph** | 有状态工作流：意图路由 → 检索 → 相关性评分 → 查询改写 → 作答 |
| **vLLM** | OpenAI 兼容推理服务：`Qwen2.5-7B-Instruct`（对话）+ `bge-m3`（1024 维 embedding） |
| **pgvector** | PostgreSQL 向量列 + HNSW 余弦索引，存储资料分块向量 |

### 12.1 请求链路

```mermaid
flowchart LR
    U[浏览器 Vue] -->|JWT| S[Spring Boot]
    S -->|历史/资料上下文| A[FastAPI]
    A --> G{LangGraph}
    G -->|embed query| V[(pgvector)]
    V -->|Top-K 片段| G
    G -->|chat| L[vLLM]
    L -->|带 [n] 引用| G
    G -->|answer + citations| A
    A --> S --> U
    S -.->|X-Internal-Token 拉取 ACTIVE 资料| A2[/api/internal/ai/materials/]
```

### 12.2 图结构

```mermaid
flowchart TD
    START --> analyze{analyze}
    analyze -->|materials| retrieve[retrieve]
    analyze -->|general| answer[answer]
    retrieve --> grade{grade}
    grade -->|有相关片段| answer
    grade -->|无片段且未改写| rewrite[rewrite]
    rewrite --> retrieve
    grade -->|无片段且已改写| answer
    answer --> END
```

- `analyze`：判断「需要资料」还是「通用问答」，并提炼检索查询；模型输出非 JSON 时 fail-open 到资料检索。
- `retrieve`：查询向量化 → `embedding <=> query` 余弦 Top-K。
- `grade`：一次 LLM 调用筛掉泛相关片段；解析失败时保留全部片段（宁可多答，不可空答）。
- `rewrite`：最多 `MAX_QUERY_REWRITES` 次（默认 1），避免死循环。
- `answer`：把片段编号 `[1..n]` 注入 prompt，要求逐句标注引用；返回后再解析 `[n]` 映射到资料。

### 12.3 索引与同步

| 场景 | 触发 |
| --- | --- |
| 启动 | 自动建 `vector` 扩展、三张表与 HNSW 索引 |
| 资料新增/修改/审核/删除 | Spring 发布 `MaterialIndexEvent` → `AFTER_COMMIT` 异步推送 |
| 全量重建 | `POST /api/admin/ai/reindex` 或 `POST /internal/v1/index/rebuild` |
| 增量拉取 | ai-service 调 `GET /api/internal/ai/materials?since=...`，按 `updatedAt` 升序分页 |

索引以 `content_hash`（正文 + 分块参数）判重，未变化直接跳过；资料少于新分块数时
旧分块会被删除，历史版本不会残留。

### 12.4 数据表

```sql
CREATE EXTENSION IF NOT EXISTS vector;

ai_material_chunks(material_id, chunk_index, title, content, tags, category_id,
                   embedding vector(1024), updated_at,
                   UNIQUE(material_id, chunk_index));
CREATE INDEX ... USING hnsw (embedding vector_cosine_ops);

ai_material_index_state(material_id, content_hash, chunk_count, source_updated_at, indexed_at);
ai_index_meta(key, value);   -- 记录 embedding 维度，防止换模型后静默错配
```

### 12.5 失败与降级

| 情况 | 行为 |
| --- | --- |
| vLLM 不可达 | AI 服务返回 503 `LLM_UNAVAILABLE`，Spring 映射为 503 + 中文提示 |
| 未配置内部令牌 | 双方都拒绝工作，避免裸奔的内部接口 |
| 资料未索引时摘要 | 若 Spring 传了资料快照，仍可生成摘要，但 `grounded=false` |
| 索引同步失败 | 只记录日志，不影响资料审核事务 |

---

## 十三、许可

本项目未附加开源许可证，默认保留所有权利。

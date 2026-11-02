<template>
  <div class="ai-workbench">
    <section class="ai-hero">
      <div class="hero-left">
        <div class="hero-title-row">
          <h2>AI 考研助手</h2>
          <el-tag size="small" type="success" effect="dark">vLLM · pgvector</el-tag>
        </div>
        <p class="hero-subtitle">围绕你的备考目标提供问答、计划建议和语义检索，帮助你更快抓住重点。</p>
        <div class="hero-stats">
          <div class="stat-item">
            <span>本次会话</span>
            <strong>{{ conversationCount }}</strong>
          </div>
          <div class="stat-item">
            <span>消息总数</span>
            <strong>{{ messages.length }}</strong>
          </div>
          <div class="stat-item">
            <span>最近历史</span>
            <strong>{{ histories.length }}</strong>
          </div>
        </div>
      </div>

      <div class="hero-right">
        <el-button type="primary" round @click="askSuggestions">
          <el-icon><Opportunity /></el-icon>
          个性化建议
        </el-button>
        <el-button round @click="clearMessages">
          <el-icon><Delete /></el-icon>
          清空会话
        </el-button>
      </div>
    </section>

    <el-row :gutter="18" class="workbench-row">
      <el-col :xs="24" :lg="16">
        <el-card class="chat-card" shadow="never">
          <template #header>
            <div class="card-header">
              <div class="header-main">
                <el-icon><ChatDotRound /></el-icon>
                <span>智能对话区</span>
              </div>
              <span class="header-hint">Enter 发送，Shift + Enter 换行</span>
            </div>
          </template>

          <div ref="msgContainer" class="messages">
            <div v-if="messages.length === 0" class="empty-chat">
              <div class="empty-icon-wrap">
                <el-icon class="empty-icon"><ChatDotRound /></el-icon>
              </div>
              <h3>你好，我是你的考研 AI 助手</h3>
              <p>你可以直接提问，也可以点击下方常用问题快速开始。</p>
              <div class="starter-list">
                <el-button
                  v-for="item in quickQuestions"
                  :key="item"
                  size="small"
                  round
                  @click="askPreset(item)"
                >
                  {{ item }}
                </el-button>
              </div>
            </div>

            <div
              v-for="(msg, i) in messages"
              :key="i"
              :class="['message-row', msg.role === 'user' ? 'user-row' : 'ai-row']"
            >
              <el-avatar
                :size="34"
                :icon="msg.role === 'user' ? 'UserFilled' : 'MagicStick'"
                :class="msg.role === 'user' ? 'user-avatar' : 'ai-avatar'"
              />
              <div class="message-body">
                <div class="message-meta">
                  {{ msg.role === 'user' ? '你' : 'AI 助手' }} · {{ msg.time }}
                  <span v-if="msg.route" class="route-badge">
                    {{ msg.route === 'materials' ? '资料增强' : '通用回答' }}
                  </span>
                </div>
                <div class="bubble" v-text="msg.content" />
                <div v-if="msg.citations && msg.citations.length" class="source-list">
                  <span class="source-label">引用</span>
                  <button
                    v-for="cite in msg.citations"
                    :key="`cite-${cite.material_id}-${cite.chunk_index}`"
                    type="button"
                    class="source-chip"
                    @click="router.push(`/materials/${cite.material_id}`)"
                  >
                    [{{ cite.index }}] {{ cite.title }}
                  </button>
                </div>
                <div v-else-if="msg.references && msg.references.length" class="source-list">
                  <span class="source-label">参考</span>
                  <button
                    v-for="ref in msg.references"
                    :key="`ref-${ref.material_id}-${ref.chunk_index}`"
                    type="button"
                    class="source-chip"
                    @click="router.push(`/materials/${ref.material_id}`)"
                  >
                    {{ ref.title }}
                  </button>
                </div>
              </div>
            </div>

            <div v-if="thinking" class="message-row ai-row">
              <el-avatar :size="34" icon="MagicStick" class="ai-avatar" />
              <div class="message-body">
                <div class="message-meta">AI 助手 · 正在思考</div>
                <div class="bubble thinking-bubble">
                  <span class="dot" />
                  <span class="dot" />
                  <span class="dot" />
                </div>
              </div>
            </div>
          </div>

          <div class="input-panel">
            <el-input
              v-model="inputText"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 5 }"
              placeholder="输入你的考研问题，比如：如何安排 90 天冲刺复习？"
              @keydown.enter.exact.prevent="sendMessage"
            />
            <div class="input-actions">
              <div class="input-tip">建议问题尽量具体，如“目标院校+科目+阶段”。</div>
              <el-button type="primary" size="large" :loading="thinking" @click="sendMessage">
                <el-icon><Position /></el-icon>
                发送问题
              </el-button>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="8" class="side-col">
        <el-card class="side-card" shadow="never">
          <template #header>
            <div class="side-title">
              <el-icon><Compass /></el-icon>
              <span>常用提问</span>
            </div>
          </template>
          <div class="quick-question-list">
            <button
              v-for="item in quickQuestions"
              :key="item"
              type="button"
              class="question-btn"
              @click="askPreset(item)"
            >
              {{ item }}
            </button>
          </div>
        </el-card>

        <el-card class="side-card" shadow="never">
          <template #header>
            <div class="side-title">
              <el-icon><Search /></el-icon>
              <span>语义搜索</span>
            </div>
          </template>

          <div class="semantic-bar">
            <el-input
              v-model="semanticQuery"
              placeholder="输入主题，例如：政治马原真题"
              @keyup.enter="handleSemanticSearch"
            />
            <el-button type="primary" plain :loading="semanticLoading" @click="handleSemanticSearch">搜索</el-button>
          </div>

          <div v-if="semanticKeywords.length" class="keyword-wrap">
            <el-tag v-for="word in semanticKeywords" :key="word" size="small" effect="plain">{{ word }}</el-tag>
          </div>

          <el-empty
            v-if="!semanticLoading && semanticResults.length === 0"
            description="暂无搜索结果"
            :image-size="56"
          />

          <div v-else class="semantic-list">
            <div v-for="item in semanticResults" :key="item.id" class="semantic-item">
              <div class="semantic-item-title" @click="router.push(`/materials/${item.id}`)">{{ item.title }}</div>
              <p class="semantic-item-desc">{{ item.description || '暂无简介' }}</p>
              <el-link type="primary" @click="router.push(`/materials/${item.id}`)">查看资料</el-link>
            </div>
          </div>
        </el-card>

        <el-card class="side-card" shadow="never">
          <template #header>
            <div class="side-title">
              <el-icon><Clock /></el-icon>
              <span>最近会话</span>
            </div>
          </template>

          <el-empty v-if="histories.length === 0" description="暂无历史记录" :image-size="56" />

          <div v-else class="history-list">
            <button
              v-for="item in histories"
              :key="item.id"
              class="history-item"
              type="button"
              @click="reuseHistory(item)"
            >
              <div class="history-head">
                <el-tag size="small" effect="plain" :type="historyTagType(item.type)">{{ historyTypeText(item.type) }}</el-tag>
                <span>{{ formatHistoryTime(item.createdAt) }}</span>
              </div>
              <p>{{ item.question }}</p>
            </button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { chat, getChatHistory, getSuggestions, semanticSearch } from '@/api/ai'

const quickQuestions = [
  '如何制定考研全年复习计划？',
  '考研数学有哪些高频考点？',
  '英语阅读如何在一个月内提升？',
  '政治主观题怎么高效背诵？'
]

const messages = ref([])
const inputText = ref('')
const thinking = ref(false)
const msgContainer = ref()
const semanticQuery = ref('')
const semanticLoading = ref(false)
const semanticResults = ref([])
const semanticKeywords = ref([])
const histories = ref([])
const router = useRouter()

const conversationCount = computed(() =>
  messages.value.filter((msg) => msg.role === 'user').length
)

onMounted(loadHistory)

async function sendMessage() {
  if (thinking.value) return

  const text = inputText.value.trim()
  if (!text) return

  pushUserMessage(text)
  inputText.value = ''
  thinking.value = true
  scrollToBottom()

  try {
    const res = await chat(text)
    pushAiMessage(res.data?.answer || '暂时没有生成回复，请稍后再试。', {
      citations: res.data?.citations || [],
      references: res.data?.references || [],
      route: res.data?.route
    })
  } catch {
    pushAiMessage('网络有点忙，请稍后再试一次。')
  } finally {
    thinking.value = false
    scrollToBottom()
    loadHistory()
  }
}

async function askSuggestions() {
  if (thinking.value) return

  const request = '请给我个性化的备考建议'
  pushUserMessage(request)
  thinking.value = true
  scrollToBottom()

  try {
    const res = await getSuggestions()
    pushAiMessage(res.data?.suggestions || '暂时无法生成建议，请稍后重试。', {
      references: res.data?.references || []
    })
  } catch {
    pushAiMessage('建议生成失败，请检查网络后再试。')
  } finally {
    thinking.value = false
    scrollToBottom()
    loadHistory()
  }
}

function askPreset(question) {
  inputText.value = question
  sendMessage()
}

async function handleSemanticSearch() {
  const q = semanticQuery.value.trim()
  if (!q) return

  semanticLoading.value = true
  try {
    const res = await semanticSearch(q)
    const data = res.data || {}
    semanticResults.value = data.materials || []
    semanticKeywords.value = Array.isArray(data.keywords) ? data.keywords : []

    if (semanticKeywords.value.length) {
      pushAiMessage(`语义命中标签：${semanticKeywords.value.join('、')}`)
      scrollToBottom()
    }
  } finally {
    semanticLoading.value = false
    loadHistory()
  }
}

async function loadHistory() {
  try {
    const res = await getChatHistory()
    histories.value = (res.data || []).slice(0, 10)
  } catch {
    histories.value = []
  }
}

function reuseHistory(item) {
  if (!item?.question) return
  inputText.value = item.question
  ElMessage.info('已带入历史问题，可直接发送或修改后发送。')
}

function clearMessages() {
  messages.value = []
  ElMessage.success('当前会话已清空')
}

function historyTypeText(type) {
  const map = {
    CHAT: '对话',
    SUMMARY: '摘要',
    SUGGESTION: '建议',
    SEARCH: '搜索'
  }
  return map[type] || '记录'
}

function historyTagType(type) {
  const map = {
    CHAT: 'primary',
    SUMMARY: 'success',
    SUGGESTION: 'warning',
    SEARCH: 'info'
  }
  return map[type] || ''
}

function formatHistoryTime(value) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hour = String(date.getHours()).padStart(2, '0')
  const minute = String(date.getMinutes()).padStart(2, '0')
  return `${month}-${day} ${hour}:${minute}`
}

function pushUserMessage(content) {
  messages.value.push({
    role: 'user',
    content,
    time: formatMessageTime(new Date())
  })
}

function pushAiMessage(content, extra = {}) {
  messages.value.push({
    role: 'ai',
    content,
    time: formatMessageTime(new Date()),
    citations: extra.citations || [],
    references: extra.references || [],
    route: extra.route || ''
  })
}

function formatMessageTime(date) {
  const hour = String(date.getHours()).padStart(2, '0')
  const minute = String(date.getMinutes()).padStart(2, '0')
  return `${hour}:${minute}`
}

function scrollToBottom() {
  nextTick(() => {
    if (msgContainer.value) {
      msgContainer.value.scrollTop = msgContainer.value.scrollHeight
    }
  })
}
</script>

<style scoped>
.ai-workbench {
  --panel: #ffffff;
  --border: #e7ecf5;
  --text-main: #1a2535;
  --text-sub: #5e6b82;
  --brand: #1e63ff;
  --brand-soft: #edf3ff;

  display: flex;
  flex-direction: column;
  gap: 14px;
}

.ai-hero {
  border-radius: 16px;
  border: 1px solid var(--border);
  background:
    radial-gradient(circle at 84% 18%, rgba(0, 184, 169, 0.18), transparent 34%),
    linear-gradient(135deg, #f9fbff 0%, #f1f6ff 50%, #ffffff 100%);
  padding: 18px 20px;
  display: flex;
  justify-content: space-between;
  gap: 14px;
}

.hero-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.hero-title-row h2 {
  font-size: 24px;
  color: var(--text-main);
  line-height: 1.2;
  margin: 0;
}

.hero-subtitle {
  color: var(--text-sub);
  font-size: 14px;
  line-height: 1.6;
}

.hero-stats {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.stat-item {
  min-width: 92px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #e6edf8;
  padding: 8px 10px;
}

.stat-item span {
  font-size: 12px;
  color: #72809a;
}

.stat-item strong {
  display: block;
  margin-top: 4px;
  font-size: 18px;
  color: #1c2638;
}

.hero-right {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.workbench-row {
  margin-top: 2px;
}

.chat-card,
.side-card {
  border-radius: 14px;
  border: 1px solid var(--border);
  background: var(--panel);
}

.chat-card {
  height: 74vh;
  display: flex;
  flex-direction: column;
}

:deep(.chat-card .el-card__header),
:deep(.side-card .el-card__header) {
  border-bottom: 1px solid #ecf0f7;
  padding: 14px 16px;
}

:deep(.chat-card .el-card__body) {
  padding: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

:deep(.side-card .el-card__body) {
  padding: 14px 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.header-main {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #1d293b;
  font-weight: 600;
}

.header-hint {
  color: #8190a8;
  font-size: 12px;
}

.messages {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  padding: 16px;
  background:
    linear-gradient(180deg, #f9fbff 0%, rgba(249, 251, 255, 0.45) 100%),
    repeating-linear-gradient(
      -45deg,
      rgba(30, 99, 255, 0.035) 0,
      rgba(30, 99, 255, 0.035) 12px,
      transparent 12px,
      transparent 24px
    );
}

.empty-chat {
  height: 100%;
  min-height: 300px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.empty-icon-wrap {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #ebf2ff;
  color: #3d74ff;
}

.empty-icon {
  font-size: 34px;
}

.empty-chat h3 {
  color: #1d293b;
  font-size: 18px;
  margin: 14px 0 8px;
}

.empty-chat p {
  color: #69758b;
  font-size: 13px;
  max-width: 400px;
}

.starter-list {
  margin-top: 14px;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 14px;
}

.user-row {
  flex-direction: row-reverse;
}

.user-avatar {
  background: linear-gradient(135deg, #1e63ff 0%, #4c87ff 100%);
}

.ai-avatar {
  background: linear-gradient(135deg, #0ea687 0%, #24c2a1 100%);
}

.message-body {
  max-width: min(74%, 620px);
}

.message-meta {
  margin-bottom: 6px;
  color: #8290a6;
  font-size: 12px;
}

.user-row .message-meta {
  text-align: right;
}

.bubble {
  padding: 11px 14px;
  border-radius: 13px;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.user-row .bubble {
  color: #fff;
  background: linear-gradient(135deg, #1e63ff 0%, #4686ff 100%);
  border-radius: 12px 2px 12px 12px;
}

.ai-row .bubble {
  color: #2f3b4e;
  background: #fff;
  border: 1px solid #e4ebf6;
  border-radius: 2px 12px 12px 12px;
}

.thinking-bubble {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}

.route-badge {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 8px;
  background: #eef4ff;
  color: #1e63ff;
  font-size: 11px;
}

.source-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 8px;
}

.source-label {
  color: #8290a6;
  font-size: 12px;
}

.source-chip {
  padding: 3px 9px;
  border: 1px solid #dfe8f6;
  border-radius: 999px;
  background: #f7faff;
  color: #2f5fd0;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.source-chip:hover {
  border-color: #a9c4ff;
  background: #eef4ff;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #9eb0cf;
  animation: blink 1.2s infinite ease-in-out;
}

.dot:nth-child(2) {
  animation-delay: 0.2s;
}

.dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.35;
  }
  40% {
    transform: translateY(-2px);
    opacity: 1;
  }
}

.input-panel {
  border-top: 1px solid #ecf0f7;
  background: #fff;
  padding: 12px 14px;
}

.input-actions {
  margin-top: 10px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.input-tip {
  color: #7e8ba0;
  font-size: 12px;
}

.side-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.side-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #1d293b;
  font-weight: 600;
}

.quick-question-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.question-btn {
  width: 100%;
  text-align: left;
  border: 1px solid #e4eaf5;
  border-radius: 10px;
  padding: 10px 12px;
  background: #fff;
  color: #354256;
  cursor: pointer;
  transition: all 0.2s ease;
}

.question-btn:hover {
  border-color: #bcd0ff;
  background: #f5f8ff;
  color: #1e63ff;
}

.semantic-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.keyword-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.semantic-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.semantic-item {
  border: 1px solid #e7edf8;
  border-radius: 10px;
  padding: 10px 12px;
  background: #fbfcff;
}

.semantic-item-title {
  font-size: 14px;
  font-weight: 600;
  color: #1d2a3e;
  cursor: pointer;
}

.semantic-item-title:hover {
  color: #1e63ff;
}

.semantic-item-desc {
  margin: 8px 0;
  color: #6b7890;
  font-size: 12px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 320px;
  overflow-y: auto;
}

.history-item {
  border: 1px solid #e6ecf6;
  border-radius: 10px;
  padding: 9px 10px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: all 0.18s ease;
}

.history-item:hover {
  border-color: #bcd0ff;
  background: #f7f9ff;
}

.history-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 7px;
}

.history-head span {
  color: #8895ab;
  font-size: 12px;
}

.history-item p {
  color: #364255;
  font-size: 13px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

@media (max-width: 1200px) {
  .chat-card {
    height: 70vh;
  }

  .message-body {
    max-width: 82%;
  }
}

@media (max-width: 992px) {
  .ai-hero {
    flex-direction: column;
  }

  .hero-right {
    align-items: center;
    justify-content: flex-start;
  }

  .chat-card {
    height: auto;
    min-height: 560px;
  }

  .messages {
    min-height: 350px;
  }

  .side-col {
    margin-top: 12px;
  }
}

@media (max-width: 680px) {
  .hero-title-row h2 {
    font-size: 20px;
  }

  .header-hint,
  .input-tip {
    display: none;
  }

  .input-actions {
    justify-content: flex-end;
  }

  .semantic-bar {
    flex-direction: column;
  }

  .message-body {
    max-width: 90%;
  }
}
</style>

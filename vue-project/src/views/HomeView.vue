<template>
  <div class="fullscreen-wrapper">
    <div class="chat-container">
      <div class="chat-header">
        <h2>智能小助手</h2>
        <div class="header-actions">
          <button @click="clearHistory" class="btn-clear" title="清空对话">
            <svg class="icon-clear" viewBox="0 0 24 24">
              <path d="M19,4H15.5L14.5,3H9.5L8.5,4H5V6H19M6,19A2,2 0 0,0 8,21H16A2,2 0 0,0 18,19V7H6V19Z" />
            </svg>
          </button>
          <span class="connection-status" :class="{ connected: isConnected }">
            {{ isConnected ? '已连接' : '未连接' }}
          </span>
        </div>
      </div>

      <div class="chat-history" ref="historyContainer">
        <div v-for="(message, index) in history" :key="index" class="message" :class="message.role">
          <div class="message-header">
            <span class="role">{{ message.role === 'user' ? '我' : 'AI助手' }}</span>
            <span class="time">{{ formatTime(message.timestamp) }}</span>
          </div>
          <div
              class="message-content"
              v-html="message.renderedContent || message.content"
          ></div>
        </div>
      </div>

      <div class="chat-input-container">
        <div class="input-wrapper">
          <textarea
              v-model="userInput"
              @keydown.enter.exact.prevent="sendMessage"
              placeholder="输入你的问题..."
              rows="1"
              :disabled="isLoading"
              @input="adjustTextareaHeight"
              ref="textarea"
          ></textarea>
          <button
              @click="sendMessage"
              :disabled="isLoading || !userInput.trim()"
              class="btn-send"
              :class="{ loading: isLoading }"
          >
            <span v-if="!isLoading">
              <svg class="icon-send" viewBox="0 0 24 24">
                <path d="M2,21L23,12L2,3V10L17,12L2,14V21Z" />
              </svg>
            </span>
            <span v-else class="loading-dots">
              <span>.</span><span>.</span><span>.</span>
            </span>
          </button>
        </div>
      </div>
    </div>
  </div>
  
</template>

<script>
import { ElMessage } from 'element-plus';
import { renderMarkdownAsync } from '@/utils/markdown';

export default {
  name: 'SparkAIChat',
  data() {
    return {
      userInput: '',
      history: [],
      isLoading: false,
      isConnected: false,
      apiBaseUrl: 'http://localhost:8080/SparkAPI',
      currentStream: null
    };
  },
  mounted() {
    this.testConnection();
    this.loadHistory();
  },
  methods: {
    isMarkdown(content) {
      if (typeof content !== 'string') return false;
      return /^#+|\[.*\]\(.*\)|`{1,3}|-\s|\*\s|\|/.test(content);
    },

    normalizeContent(content) {
      if (!content) return '';
      return content
          .trim()
          // 移除多余的 $ 符号（保留公式内容）
          .replace(/\$\s*\$/g, ' ')  // 替换 $$ 为空格
          .replace(/\$(.*?)\$/g, '$1') // 提取 $...$ 中的内容
          // 移除所有开头和结尾的空白符（包括换行）
          // 压缩连续换行（保留最多两个）
          .replace(/\n{3,}/g, '\n\n')
          // 确保最后没有多余换行
          .replace(/\n+$/, '');
    },

    async renderMarkdown(content) {
      console.log('原始内容:', JSON.stringify(content));
      console.log('处理后:', JSON.stringify(this.normalizeContent(content)));
      if (!content) return '';
      try {
        const normalized = this.normalizeContent(content);
        return await renderMarkdownAsync(normalized);
      } catch (error) {
        console.error('渲染Markdown失败:', error);
        return content;
      }
    },

    adjustTextareaHeight() {
      const textarea = this.$refs.textarea;
      textarea.style.height = 'auto';
      textarea.style.height = `${Math.min(textarea.scrollHeight, 150)}px`;
    },

    async sendMessage() {
      if (!this.userInput.trim() || this.isLoading) return;

      try {
        this.isLoading = true;

        // 用户消息
        const userMessage = {
          role: 'user',
          content: this.userInput,
          renderedContent: await this.renderMarkdown(this.userInput), // 预渲染
          timestamp: new Date()
        };
        this.history.push(userMessage);

        // AI消息（初始空内容）
        const aiMessage = {
          role: 'assistant',
          content: '',
          renderedContent: '',
          timestamp: new Date()
        };
        this.history.push(aiMessage);

        this.scrollToBottom();

        const question = this.userInput;
        this.userInput = '';

        // 创建新的EventSource连接
        this.currentStream = new EventSource(`${this.apiBaseUrl}/chatStream?question=${encodeURIComponent(question)}`);

        this.currentStream.onopen = () => {
          console.log('SSE连接已建立');
        };

        this.currentStream.onmessage = async (event) => {
          try {
            const data = JSON.parse(event.data);
            if (this.history.length > 0) {
              const lastMessage = this.history[this.history.length - 1];
              if (lastMessage.role === 'assistant') {
                // 清理数据中的 $ 符号
                const cleanContent = data.content.replace(/\$\s*\$/g, ' ').replace(/\$(.*?)\$/g, '$1');
                lastMessage.content += cleanContent;
                lastMessage.renderedContent = await this.renderMarkdown(lastMessage.content);
                this.scrollToBottom();
              }
            }
            if (data.finished) this.closeStream();
          } catch (e) {
            console.error('解析SSE消息失败:', e);
          }
        };

        this.currentStream.onerror = (error) => {
          // 忽略连接关闭时的错误
          if (this.currentStream.readyState === EventSource.CLOSED) {
            console.log('SSE连接正常关闭');
            return;
          }
          this.closeStream();
        };

      } catch (error) {
        console.error('发送消息失败:', error);
        ElMessage.error('发送消息失败: ' + error.message);
        this.isLoading = false;
      }
    },

    closeStream() {
      if (this.currentStream) {
        // 先设置readyState检查，避免重复关闭
        if (this.currentStream.readyState !== EventSource.CLOSED) {
          this.currentStream.close();
        }
        this.currentStream = null;
      }
      this.isLoading = false;
    },

    async loadHistory() {
      try {
        const response = await fetch(`${this.apiBaseUrl}/getHistory`, {
          headers: {
            'Accept': 'application/json'
          }
        });

        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);

        const data = await response.json();
        this.history = await Promise.all(data.map(async item => ({
          role: item.role,
          content: item.content,
          renderedContent: await this.renderMarkdown(item.content), // 预渲染
          timestamp: new Date()
        })));

        this.scrollToBottom();
      } catch (error) {
        console.error('加载历史记录失败:', error);
        ElMessage.error('加载历史记录失败: ' + error.message);
      }
    },

    async clearHistory() {
      try {
        await fetch(`${this.apiBaseUrl}/delHistory`, { method: 'DELETE' });
        this.history = [];
        ElMessage.success('对话历史已清空');
      } catch (error) {
        console.error('清空历史记录失败:', error);
        ElMessage.error('清空历史记录失败');
      }
    },

    async testConnection() {
      try {
        const response = await fetch(`${this.apiBaseUrl}/testConnection`, {
          headers: {
            'Accept': 'application/json'
          }
        });

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();
        // 修改判断逻辑，直接使用data.status或data.data
        this.isConnected = data.status === 200;

        if (!this.isConnected) {
          ElMessage.error(data.message);
        } else {
          ElMessage.success(data.message); // 添加成功提示
        }
      } catch (error) {
        this.isConnected = false;
        console.error('连接测试失败:', error);
        ElMessage.error('连接测试失败: ' + error.message);
      }
    },

    scrollToBottom() {
      this.$nextTick(() => {
        const container = this.$refs.historyContainer;
        container.scrollTop = container.scrollHeight;
      });
    },

    formatTime(date) {
      return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
  },

  beforeUnmount() {
    this.closeStream();
  }
};
</script>

<style scoped>
.fullscreen-wrapper {
  width: 100%;
  height: 100%;
  display: flex;
  justify-content: center; /* 水平居中 */
  background-color: #f5f5f5; /* 可选背景色 */
  padding: 0 100px;
}

.chat-container {
  width: 70vw; /* 保持你设置的70%视口宽度 */
  height: 100vh; /* 100%视口高度 */
  display: flex;
  flex-direction: column;
  background-color: #f5f5f5;
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
  padding-bottom: 10px;
  border-bottom: 1px solid #e0e0e0;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.connection-status {
  padding: 4px 8px;
  border-radius: 12px;
  font-size: 0.75rem;
  font-weight: 500;
  background-color: #ff6b6b;
  color: white;
}

.connection-status.connected {
  background-color: #51cf66;
}

.chat-history {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  background-color: white;
  border-radius: 8px;
  margin-bottom: 12px;
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.05);
}

.message {
  margin-bottom: 10px;
  padding: 10px 14px;
  border-radius: 8px;
  max-width: 85%;
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(5px); }
  to { opacity: 1; transform: translateY(0); }
}

.message-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
  font-size: 0.75rem;
  color: #666;
}

.message-content {
  /* 完全清空样式 */
  all: initial;
  /* 仅保留必要的基础样式 */
  display: block;
  white-space: normal; /* 禁用pre-wrap的严格换行 */
  word-break: break-word;
}

/* 调整消息对齐方式 */
.user {
  align-self: flex-end; /* 用户消息右对齐 */
  background-color: #e3f2fd;
  border-bottom-right-radius: 4px;
  margin-left: auto; /* 确保靠右 */
}

.assistant {
  align-self: flex-start; /* AI消息左对齐 */
  background-color: #f8f9fa;
  border-bottom-left-radius: 4px;
  margin-right: auto; /* 确保靠左 */
}

.chat-input-container {
  padding: 8px;
  background-color: white;
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
}

.input-wrapper {
  position: relative;
  display: flex;
  align-items: flex-end;
}

textarea {
  flex: 1;
  padding: 12px 48px 12px 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  resize: none;
  font-family: inherit;
  font-size: 0.95rem;
  line-height: 1.5;
  min-height: 48px;
  max-height: 150px;
  transition: border-color 0.2s;
}

textarea:focus {
  outline: none;
  border-color: #4a89dc;
  box-shadow: 0 0 0 2px rgba(74, 137, 220, 0.2);
}

.btn-send {
  position: absolute;
  right: 8px;
  bottom: 8px;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #4a89dc;
  color: white;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-send:hover:not(:disabled) {
  background-color: #3b7dd8;
  transform: scale(1.05);
}

.btn-send:disabled {
  background-color: #ccc;
  cursor: not-allowed;
}

.icon-send {
  width: 18px;
  height: 18px;
  fill: currentColor;
}

.btn-clear {
  width: 32px;
  height: 32px;
  padding: 6px;
  background-color: transparent;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-clear:hover {
  background-color: rgba(231, 76, 60, 0.1);
}

.icon-clear {
  width: 20px;
  height: 20px;
  fill: #e74c3c;
}

.loading-dots {
  display: inline-flex;
  align-items: center;
  height: 100%;
}

.loading-dots span {
  animation: blink 1.4s infinite both;
  font-size: 1.2rem;
  line-height: 1;
}

.loading-dots span:nth-child(2) {
  animation-delay: 0.2s;
}

.loading-dots span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%, 100% { opacity: 0.2; }
  50% { opacity: 1; }
}

@media (max-width: 768px) {
  .chat-container {
    width: 90%; /* 在小屏幕上占据90%宽度 */
    height: 90vh; /* 适当调整高度 */
  }
}

/* 基础重置 */
.message-content :deep(*) {
  all: initial;
  display: block;
  margin: 0;
  padding: 0;
  line-height: 1.6;  /* 更舒适的行高 */
  color: inherit;    /* 继承父元素文字颜色 */
}

/* 基础文本重置 */
.message-content {
  line-height: 1.6;
  text-align: justify;
}

/* 完全控制strong标签及其后内容 */
.message-content :deep(strong) {
  font-weight: bold;
  display: inline; /* 确保不换行 */
}

/* 特殊处理中文冒号情况 */
.message-content :deep(strong)::after {
  content: ""; /* 清除默认内容 */
  display: none; /* 完全隐藏 */
}

/* 标准段落样式 */
.message-content :deep(p) {
  margin-bottom: 0.4em;
  text-indent: 2em; /* 中文标准缩进 */
  text-align: left; /* 左对齐更自然 */
}

/* 列表项特殊处理 */
.message-content :deep(li) {
  text-indent: 2em; /* 取消列表项缩进 */
  margin-bottom: 0.2em;
}

/* 优化后的标题层级样式 */
.message-content :deep(h1) {
  font-size: 1.6em;    /* 最大字号 */
  font-weight: bold;
  margin: 0.8em 0 0.5em;
  line-height: 1.3;
  color: #333;         /* 深色增强可读性 */
}

.message-content :deep(h2) {
  font-size: 1.4em;    /* 次级标题 */
  font-weight: bold;
  margin: 0.7em 0 0.45em;
  line-height: 1.35;
  color: #444;
}

.message-content :deep(h3) {
  font-size: 1.2em;    /* 三级标题 */
  font-weight: bold;
  margin: 0.6em 0 0.4em;
  line-height: 1.4;
  color: #555;
}

.message-content :deep(h4) {
  font-size: 1.1em;    /* 最小标题 */
  font-weight: bold;
  margin: 0.5em 0 0.35em;
  line-height: 1.45;
  color: #666;
}

/* 代码块样式 */
.message-content :deep(pre) {
  margin-bottom: 0.4em !important;
  padding: 0.6em !important;
  background-color: #f5f5f5;
  border-radius: 4px;
  overflow-x: auto;
}
.message-content :deep(code) {
  font-family: monospace;
  font-size: 0.9em;
}

/* 链接样式 */
.message-content :deep(a) {
  color: #4a89dc;
  text-decoration: underline;
  cursor: pointer;
}

</style>
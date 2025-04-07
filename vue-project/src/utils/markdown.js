import { marked } from 'marked';
import DOMPurify from 'dompurify';

marked.setOptions({
    breaks: true,
    gfm: true,
    headerIds: false,  // 禁用自动生成id
    mangle: false ,    // 禁用特殊字符转换
    pedantic: false,
});

export async function renderMarkdownAsync(content) {
    if (!content) return '';
    try {
        const html = await marked.parse(content);
        return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } }); // 明确指定配置
    } catch (error) {
        console.error('Markdown渲染错误:', error);
        return DOMPurify.sanitize(content);
    }
}
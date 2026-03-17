// API 基础地址，优先读取环境变量 UMI_APP_API_BASE_URL，未配置时使用当前域名
// 本地开发：在 .env.local 中配置 UMI_APP_API_BASE_URL=http://localhost:8080
// 服务器部署：不配置此变量，自动使用 window.location.origin
export const API_BASE_URL = process.env.UMI_APP_API_BASE_URL || window.location.origin;

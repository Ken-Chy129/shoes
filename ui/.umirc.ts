import { defineConfig } from '@umijs/max';

export default defineConfig({
  antd: {},
  access: {},
  model: {},
  initialState: {},
  request: {},
  layout: {
    title: '鞋子管理系统',
  },
  routes: [
    { 
      path: '/login',
      component: 'login',
      layout: false
    },
    {
      path: '/',
      component: '@/layouts/index',
      routes: [
        { 
          path: '/', 
          redirect: '/price'
        },
        { 
          path: '/price',
          name: '价格查询',
          component: 'price/index'
        },
        {
          path: '/price-update',
          name: '价格更新',
          component: 'price/update'
        }
      ]
    }
  ],
  npmClient: 'pnpm',
  proxy: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true,
      pathRewrite: { '^/api': '' },
    },
  },
});

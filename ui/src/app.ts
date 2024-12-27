import { RequestConfig } from '@umijs/max';
import { message } from 'antd';

export const request: RequestConfig = {
  timeout: 10000,
  errorConfig: {
    errorHandler: (error: any) => {
      console.error('Request error:', error);
      message.error(error.message);
    },
  },
  requestInterceptors: [
    (url, options) => {
      console.log('Request interceptor - URL:', url);
      console.log('Request interceptor - Options:', options);
      
      const token = localStorage.getItem('token');
      console.log('Request interceptor - Token:', token);
      
      // 对于登录请求，不需要添加 token
      if (url.includes('/login')) {
        console.log('Login request - skipping token');
        return { url, options };
      }

      // 对于其他请求，必须添加 token
      if (!token) {
        console.log('No token found for authenticated request');
        throw new Error('No token found');
      }

      const authHeader = `Bearer ${token}`;
      console.log('Adding authorization header:', authHeader);

      const newOptions = {
        ...options,
        headers: {
          ...options.headers,
          'Authorization': authHeader,
        },
      };
      
      console.log('Final request options:', newOptions);
      return { url, options: newOptions };
    },
  ],
  responseInterceptors: [
    (response) => {
      console.log('Response:', response);
      return response;
    },
  ],
}; 
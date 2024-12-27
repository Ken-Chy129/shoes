import { RequestConfig } from '@umijs/max';
import { message } from 'antd';

export const request: RequestConfig = {
  timeout: 10000,
  errorConfig: {
    errorHandler: (error: any) => {
      message.error(error.message);
    },
  },
  requestInterceptors: [
    (url, options) => {
      return {
        url,
        options: {
          ...options,
          headers: {
            ...options.headers,
          },
        },
      };
    },
  ],
  responseInterceptors: [
    (response) => {
      return response;
    },
  ],
}; 
import { extend } from 'umi-request';
import { message } from 'antd';

const request = extend({
  prefix: '',
  timeout: 10000,
  errorHandler: (error: any) => {
    message.error(error.message);
    throw error;
  },
});

request.interceptors.request.use((url, options) => {
  return {
    url,
    options: {
      ...options,
      headers: {
        ...options.headers,
      },
    },
  };
});

request.interceptors.response.use(async (response) => {
  return response;
});

export default request; 
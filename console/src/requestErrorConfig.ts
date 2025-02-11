import type { RequestOptions } from '@@/plugin-request/request';
import type { RequestConfig } from '@umijs/max';
import { message, notification } from 'antd';


// 与后端约定的响应数据格式
interface ResponseStructure {
  success: boolean;
  data: any;
  errorCode?: string;
  errorMsg?: string;
  traceId?: string;
  ip?: string;

  pageIndex?: number;
  pageSize?: number;
  total?: number;
  pageCount?: number;
  hasMore?: boolean;
}

/**
 * @name 错误处理
 * pro 自带的错误处理， 可以在这里做自己的改动
 * @doc https://umijs.org/docs/max/request#配置
 */
export const errorConfig: RequestConfig = {
  // 错误处理： umi@3 的错误处理方案。
  // errorConfig: {
  //   // 错误抛出
  //   errorThrower: (res) => {
  //     const { success, data, errorMessage } =
  //       res as unknown as ResponseStructure;
  //     if (!success) {
  //       ms.error(errorMessage);
  //     }
  //   },
  //   // 错误接收及处理
  //   errorHandler: (error: any, opts: any) => {
  //     if (opts?.skipErrorHandler) throw error;
  //     // 我们的 errorThrower 抛出的错误。
  //     if (error.name === 'BizError') {
  //       const errorInfo: ResponseStructure | undefined = error.info;
  //       if (errorInfo) {
  //         const { errorMessage, errorCode } = errorInfo;
  //         switch (errorInfo.showType) {
  //           case ErrorShowType.SILENT:
  //             // do nothing
  //             break;
  //           case ErrorShowType.WARN_MESSAGE:
  //             ms.warning(errorMessage);
  //             break;
  //           case ErrorShowType.ERROR_MESSAGE:
  //             ms.error(errorMessage);
  //             break;
  //           case ErrorShowType.NOTIFICATION:
  //             notification.open({
  //               description: errorMessage,
  //               message: errorCode,
  //             });
  //             break;
  //           case ErrorShowType.REDIRECT:
  //             // TODO: redirect
  //             break;
  //           default:
  //             ms.error(errorMessage);
  //         }
  //       }
  //     } else if (error.response) {
  //       // Axios 的错误
  //       // 请求成功发出且服务器也响应了状态码，但状态代码超出了 2xx 的范围
  //       message.error(`Response status:${error.response.status}`);
  //     } else if (error.request) {
  //       // 请求已经成功发起，但没有收到响应
  //       // \`error.request\` 在浏览器中是 XMLHttpRequest 的实例，
  //       // 而在node.js中是 http.ClientRequest 的实例
  //       message.error('None response! Please retry.');
  //     } else {
  //       // 发送请求时出了点问题
  //       message.error('Request error, please retry.');
  //     }
  //   },
  // },

  // 请求拦截器
  requestInterceptors: [
    (config: RequestOptions) => {
      return { ...config };
    },
  ],

  // 响应拦截器
  responseInterceptors: [
    (response) => {
      // 拦截响应数据，进行个性化处理
      const { data } = response as unknown as ResponseStructure;

      if (data?.success === false) {
        console.log(data);
        notification.open({
          description: data?.errorMsg,
          message: data?.errorCode,
        });
        // message.error(data?.errorMsg);
      }
      return response;
    },
  ],
};

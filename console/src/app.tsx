import { Footer, Question, SelectLang, AvatarDropdown, AvatarName } from '@/components';
import { LinkOutlined } from '@ant-design/icons';
import type {Settings as LayoutSettings} from '@ant-design/pro-components';
import { SettingDrawer } from '@ant-design/pro-components';
import type { RunTimeLayoutConfig } from '@umijs/max';
import { history, Link } from '@umijs/max';
import defaultSettings from '../config/defaultSettings';
import { errorConfig } from './requestErrorConfig';
import React from 'react';
import {doGetRequest} from "@/util/http";
import {USER_API} from "@/services/user";

const isDev = process.env.NODE_ENV === 'development';
const loginPath = '/user/login';

/**
 * @see  https://umijs.org/zh-CN/plugins/plugin-initial-state
 * */
export async function getInitialState(): Promise<{
  settings?: Partial<LayoutSettings>;
  currentUser?: API.CurrentUser;
  loading?: boolean;
  fetchUserInfo?: () => Promise<API.CurrentUser | undefined>;
}> {
  const fetchUserInfo = async () => {
    let msg;
    doGetRequest(USER_API.CURRENT, {}, {
      onSuccess: res => msg = res.data,
      onError: _ => history.push(loginPath)
    })
    return msg
  };
  // 如果不是登录页面，执行
  const { location } = history;
  if (location.pathname !== loginPath) {
    try {
      // 获取当前用户信息
      const currentUser = await fetchUserInfo();
      return {
        fetchUserInfo,
        currentUser,
        settings: defaultSettings as Partial<LayoutSettings>,
      };
    } catch (error) {
      // 如果出错,不要直接返回空对象
      // return {};
      return {
        fetchUserInfo,
        currentUser: undefined, 
      };
    }
  }
  return {
    fetchUserInfo,
    settings: defaultSettings as Partial<LayoutSettings>,
  };
}

// ProLayout 支持的api https://procomponents.ant.design/components/layout
export const layout: RunTimeLayoutConfig = ({ initialState, setInitialState }) => {

  return {
    title: "Master",
    openKeys: false,
    actionsRender: () => [<Question key="doc" />, <SelectLang key="SelectLang" />],
    avatarProps: {
      src: initialState?.currentUser?.avatar,
      title: <AvatarName />,
      render: (_, avatarChildren) => {
        return <AvatarDropdown>{avatarChildren}</AvatarDropdown>;
      },
    },
    waterMarkProps: {
      content: initialState?.currentUser?.name,
    },
    footerRender: () => <Footer />,
    onPageChange: () => {
      const { location } = history;
      // 如果没有登录，重定向到 login
      // const token = localStorage.getItem('token');
      const token = sessionStorage.getItem('token');
      if (!token && location.pathname !== loginPath) {
        history.push(loginPath);
      }
    },
    bgLayoutImgList: [
      {
        src: 'https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/D2LWSqNny4sAAAAAAAAAAAAAFl94AQBr',
        left: 85,
        bottom: 100,
        height: '303px',
      },
      {
        src: 'https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/C2TWRpJpiC0AAAAAAAAAAAAAFl94AQBr',
        bottom: -68,
        right: -45,
        height: '303px',
      },
      {
        src: 'https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/F6vSTbj8KpYAAAAAAAAAAAAAFl94AQBr',
        bottom: 0,
        left: 0,
        width: '331px',
      },
    ],
    links: isDev
      ? [
          <a href="https://github.com/Ken-Chy129" target="_blank">
            <LinkOutlined />
            <span>联系作者</span>
          </a>,
        ]
      : [],
    menuHeaderRender: undefined,
    // 自定义 403 页面
    unAccessible: <div>unAccessible</div>,
    // 增加一个 loading 的状态
    breakpoint: false,
    postMenuData: () => {
      const data = [
        {
          name: "配置信息",
          path: "setting"
        },
        {
          name: "kickscrew",
          children: [
            {
              name: "品牌信息",
              path: "kc/brand"
            },
            {
              name: "特殊货号管理",
              path: "kc/model"
            },
            {
              name: "订单信息",
              path: "kc/order"
            }
          ]
        },
        {
          name: "stockx",
          children: [
            {
              name: "品牌信息",
              path: "stockx/brand"
            },
            {
              name: "订单信息",
              path: "stockx/order"
            },
            {
              name: "搜索",
              path: "stockx/search"
            },
          ]
        },
        {
          name: "dunk",
          children: [
            {
              name: "搜索",
              path: "dunk/search"
            },
          ]
        },
        {
          name: "得物",
          path: "/poison",
        },
        {
          name: "任务",
          children: [
            {
              name: "执行器",
              path: "task/executor"
            },
            {
              name: "历史任务",
              path: "task/history"
            },
          ]
        },
        {
          name: "工具",
          children: [
            {
              name: "查价",
              path: "/tool/price"
            },
            {
              name: "尺码表",
              path: "/tool/1"
            },
          ]
        },
        {
          name: "文件",
          path: "/file"
        }
      ]
      return data;
    },
    childrenRender: (children) => {
      // if (initialState?.loading) return <PageLoading />;
      return (
        <>
          {children}
          {isDev && (
            <SettingDrawer
              disableUrlParams
              enableDarkTheme
              settings={initialState?.settings}
              onSettingChange={(settings) => {
                setInitialState((preInitialState) => ({
                  ...preInitialState,
                  settings,
                }));
              }}
            />
          )}
        </>
      );
    },
    ...initialState?.settings,
  };
};

/**
 * @name request 配置，可以配置错误处理
 * 它基于 axios 和 ahooks 的 useRequest 提供了一套统一的网络请求和错误处理方案。
 * @doc https://umijs.org/docs/max/request#配置
 */
export const request = {
  ...errorConfig,
};

// // 路由权限控制
// export function onRouteChange({ location, routes, action }) {
//   const { currentUser } = getInitialState();
//   const isLogin = !!currentUser;
//   const isLoginPage = location.pathname === '/user/login';
//
//   // 如果未登录且不是登录页,重定向到登录页
//   if (!isLogin && !isLoginPage) {
//     history.push('/user/login');
//   }
//
//   // 如果已登录且是登录页,重定向到首页
//   if (isLogin && isLoginPage) {
//     history.push('/');
//   }
// }


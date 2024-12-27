import { RunTimeLayoutConfig, history } from '@umijs/max';
import { message } from 'antd';
import { fetchUserInfo } from '@/services/user';

// 定义全局初始状态类型
export interface InitialState {
  currentUser?: {
    username: string;
    nickname: string;
  };
  loading?: boolean;
}

// 全局初始化状态
export async function getInitialState(): Promise<InitialState> {
  const { location } = history;
  
  // 如果是登录页面，不获取用户信息
  if (location.pathname === '/login') {
    return {
      loading: false,
    };
  }
  
  try {
    const token = localStorage.getItem('token');
    if (!token) {
      return {
        loading: false,
      };
    }

    const currentUser = await fetchUserInfo();
    
    if (!currentUser) {
      return {
        loading: false,
      };
    }
    
    return {
      currentUser,
      loading: false,
    };
  } catch (error) {
    localStorage.removeItem('token');
    return {
      loading: false,
    };
  }
}

// 布局运行时配置
export const layout: RunTimeLayoutConfig = ({ initialState, setInitialState }) => {
  const layoutConfig = {
    logo: 'https://img.alicdn.com/tfs/TB1YHEpwUT1gK0jSZFhXXaAtVXa-28-27.svg',
    menu: {
      locale: false,
    },
    logout: async () => {
      localStorage.removeItem('token');
      await setInitialState((s) => ({ ...s, currentUser: undefined }));
      message.success('退出登录成功！');
      history.replace('/login');
    },
    rightRender: () => {
      return initialState?.currentUser?.nickname || initialState?.currentUser?.username;
    },
  };

  return layoutConfig;
}; 
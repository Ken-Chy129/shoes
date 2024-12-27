import { request } from '@umijs/max';

export interface LoginParams {
  username: string;
  password: string;
}

export interface UserInfo {
  id: number;
  username: string;
  nickname: string;
}

export async function login(params: LoginParams) {
  return request<{
    code: number;
    msg?: string;
    data: string;
  }>('/api/user/login', {
    method: 'POST',
    data: params,
  });
}

export async function getCurrentUser() {
  try {
    const token = localStorage.getItem('token');
    if (!token) {
      throw new Error('No token found');
    }

    console.log('Sending getCurrentUser request...');
    console.log('Using token:', token);
    
    const response = await request<{
      code: number;
      msg?: string;
      data: UserInfo;
    }>('/api/user/current', {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`,
      },
      skipErrorHandler: true,
    });

    console.log('getCurrentUser raw response:', response);
    return response;
  } catch (error) {
    console.error('getCurrentUser error:', error);
    throw error;
  }
}

export async function fetchUserInfo() {
  try {
    const token = localStorage.getItem('token');
    if (!token) {
      throw new Error('No token found');
    }
    
    const res = await getCurrentUser();
    
    if (res.code === 200 && res.data) {
      return {
        username: res.data.username,
        nickname: res.data.nickname,
      };
    }
    
    localStorage.removeItem('token');
    throw new Error(res.msg || 'Failed to get user info');
  } catch (error) {
    localStorage.removeItem('token');
    throw error;
  }
} 
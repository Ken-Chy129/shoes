import { request } from '@umijs/max';

export async function outLogin(options?: { [key: string]: any }) {
    return request<Record<string, any>>('/api/login/outLogin', {
        method: 'POST',
        ...(options || {}),
    });
}

/** 获取当前的用户 GET /api/currentUser */
export async function currentUser(options?: { [key: string]: any }) {
    return request<{
        data: API.CurrentUser;
    }>('/api/currentUser', {
        method: 'GET',
        ...(options || {}),
    });
}

/** 发送验证码 POST /api/login/captcha */
export async function getFakeCaptcha(
    params: {
        // query
        /** 手机号 */
        phone?: string;
    },
    options?: { [key: string]: any },
) {
    return request<API.FakeCaptcha>('/api/login/captcha', {
        method: 'GET',
        params: {
            ...params,
        },
        ...(options || {}),
    });
}

export async function login(body: API.LoginParams, options?: { [key: string]: any }) {
    return request<API.LoginResult>('/api/login/account', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        data: body,
        ...(options || {}),
    });
}

export async function getMachineList(params: {
    appId: string
}, options?: { [key: string]: any }) {
    return request<API.LoginResult>('/api/master/machines', {
        method: 'GET',
        params: {
            ...params,
        },
        ...(options || {}),
    });
}

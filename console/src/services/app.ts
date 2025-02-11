import { request } from '@umijs/max';

export async function getCategoryList() {
    return request('/api/master/categories', {
        method: 'GET'
    })
}

enum APP_API {
    SAVE = '/api/app/save',
    LIST = '/api/app/list',
}

enum MACHINE_API {
    LIST = '/api/machine/list',
}

export {APP_API, MACHINE_API}
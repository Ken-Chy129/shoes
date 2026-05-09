import {request} from "@@/exports";
import {message} from "antd";
import {RcFile} from "antd/es/upload/interface";

function doGetRequest(
    apiName: string,
    params: {},
    recall: {
        onSuccess: (response: any) => void,
        onError?: (response: any) => void,
        onFinally?: () => void
    }
) {
    request(apiName, {
        method: 'GET',
        params
    }).then((res) => {
        (res.success ? recall.onSuccess : recall.onError)?.(res);
    }).catch(() => {
        message.error("系统异常").then(_ => {});
    }).finally(() => {
        recall.onFinally?.();
    });
}

function doPostRequest(
    apiName: string,
    data: {},
    recall: {
        onSuccess: (response: any) => void,
        onError?: (response: any) => void,
        onFinally?: () => void
    }
) {
    request(apiName, {
        method: 'POST',
        data
    }).then((res) => {
        (res.success ? recall.onSuccess : recall.onError)?.(res);
    }).catch(() => {
        message.error("系统异常").then(_ => {});
    }).finally(() => {
        recall.onFinally?.();
    });
}

function doDeleteRequest(
    apiName: string,
    params: {},
    recall: {
        onSuccess: (response: any) => void,
        onError?: (response: any) => void,
        onFinally?: () => void
    }
) {
    request(apiName, {
        method: 'DELETE',
        params
    }).then((res) => {
        (res.success ? recall.onSuccess : recall.onError)?.(res);
    }).catch(() => {
        message.error("系统异常").then(_ => {});
    }).finally(() => {
        recall.onFinally?.();
    });
}

function doUploadRequest(
    apiName: string,
    file: RcFile
) {
    const formData = new FormData();
    formData.append("file", file);
    request(apiName, {
        method: 'POST',
        formData,
        headers: {'Content-Type': 'multipart/form-data'}
    });
}

function doUploadRequestWithParams(
    apiName: string,
    file: RcFile,
    params?: Record<string, string>,
    callbacks?: { onSuccess?: (res: any) => void; onError?: (err: any) => void }
) {
    const formData = new FormData();
    formData.append("file", file);
    if (params) {
        Object.entries(params).forEach(([key, value]) => formData.append(key, value));
    }
    request(apiName, {
        method: 'POST',
        data: formData,
    }).then(res => {
        callbacks?.onSuccess?.(res);
    }).catch(err => {
        callbacks?.onError?.(err);
    });
}


export {doGetRequest, doPostRequest, doDeleteRequest, doUploadRequest, doUploadRequestWithParams}
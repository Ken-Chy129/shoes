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


export {doGetRequest, doPostRequest, doDeleteRequest, doUploadRequest}
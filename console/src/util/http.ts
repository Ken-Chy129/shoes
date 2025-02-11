import {request} from "@@/exports";
import {message} from "antd";

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


export {doGetRequest, doPostRequest, doDeleteRequest}
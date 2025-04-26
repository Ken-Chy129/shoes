import {
    Button, Card,
    Form,
    Input, message, Select,
    Table, Upload,
} from "antd";
import React, {useEffect, useState} from "react";
import {DownloadOutlined, UploadOutlined} from "@ant-design/icons";
import {doPostRequest, doUploadRequest} from "@/util/http";
import {UPLOAD_API} from "@/services/file";

const SettingPage = () => {
    const [fileUpload] = Form.useForm();
    const [fileDownload] = Form.useForm();

    useEffect(() => {

    }, []);


    const downloadOrders = () => {
        window.open('http://localhost:8080/order/kc/excel');
    }

    return <>
        <Card title={"文件上传"}>
            <Form form={fileUpload}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="apiMode">
                        <Upload
                            accept={".txt"}
                            beforeUpload={(file) => {
                                doUploadRequest(UPLOAD_API.UPLOAD, file);
                                return false; // 阻止默认上传行为
                            }}
                            showUploadList={false} // 不显示上传列表
                        >
                            <Button icon={<UploadOutlined/>}>瑕疵货号上传</Button>
                        </Upload>
                    </Form.Item>
                    <Form.Item name="apiMode" style={{marginLeft: 50}}>
                        <Upload
                            accept={".txt"}
                            beforeUpload={(file) => {
                                // handleUpload(file); // 手动处理文件上传
                                return false; // 阻止默认上传行为
                            }}
                            showUploadList={false} // 不显示上传列表
                        >
                            <Button icon={<UploadOutlined/>}>不压价商品上传</Button>
                        </Upload>
                    </Form.Item>
                    <Form.Item name="apiMode" style={{marginLeft: 50}}>
                        <Upload
                            accept={".txt"}
                            beforeUpload={(file) => {
                                // handleUpload(file); // 手动处理文件上传
                                return false; // 阻止默认上传行为
                            }}
                            showUploadList={false} // 不显示上传列表
                        >
                            <Button icon={<UploadOutlined/>}>3.5商品上传</Button>
                        </Upload>
                    </Form.Item>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"文件下载"}>
            <Form form={fileDownload}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="exchangeRate">
                        <Button icon={<DownloadOutlined/>} onClick={downloadOrders}>
                            KC订单导出
                        </Button>
                    </Form.Item>
                    <Form.Item name="freight" style={{marginLeft: 50}}>
                        <Button icon={<DownloadOutlined/>} onClick={downloadOrders}>
                            KC订单Label导出
                        </Button>
                    </Form.Item>
                    <Form.Item name="minProfit" style={{marginLeft: 50}}>
                        <Button icon={<DownloadOutlined/>} onClick={downloadOrders}>
                            订单导出
                        </Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
        <br/>
    </>
}

export default SettingPage;
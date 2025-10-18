import {
    Button, Card,
    Form,
    Input, message, Select,
    Table, Upload,
} from "antd";
import React, {useEffect, useState} from "react";
import {DownloadOutlined, UploadOutlined} from "@ant-design/icons";
import {doGetRequest, doPostRequest, doUploadRequest} from "@/util/http";
import {STOCKX_DOWNLOAD_API, UPLOAD_API} from "@/services/file";

const SettingPage = () => {
    const [fileUpload] = Form.useForm();
    const [fileDownload] = Form.useForm();
    const [stockxDownload] = Form.useForm();

    useEffect(() => {

    }, []);


    const downloadOrders = () => {
        window.open('http://localhost:8080/order/kc/excel');
    }

    const downloadStockXItems = () => {
        const query = stockxDownload.getFieldValue("query");
        const sortType = stockxDownload.getFieldValue("sortType");
        window.open('http://localhost:8080/file/downloadItemsForSearch?query=' + query + '&sortType=' + sortType)
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
        <Card title={"绿叉导出"}>
            <Form form={stockxDownload}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="query" label="关键词搜索">
                        <Input/>
                    </Form.Item>
                    <Form.Item name="sortType" label="排序" style={{marginLeft: 20}}>
                        <Select
                            style={{width: 160}}
                            placeholder="请选择字段"
                            allowClear
                            optionFilterProp="label"
                            defaultValue={'featured'}
                            options={
                                [
                                    {label: '精选', value: 'featured'},
                                    {label: 'Top Selling', value: 'most-active'},
                                    {label: 'Price: Low to High', value: 'lowest_ask'},
                                    {label: '出价: 从高到低', value: 'highest_bid'},
                                    {label: 'Recent High Bids', value: 'recent_bids'},
                                    {label: 'Recent Price Drops', value: 'recent_asks'},
                                    {label: 'Total Sold: High to Low', value: 'deadstock_sold'},
                                    {label: '发布日期', value: 'release_date'},
                                    {label: 'Price Premium: High to Low', value: 'price_premium'},
                                    {label: 'Last Sale: High to Low', value: 'last_sale'},
                                ]
                            }
                        />
                    </Form.Item>
                    <Form.Item style={{marginLeft: 50}}>
                        <Button icon={<DownloadOutlined/>} onClick={downloadStockXItems}>
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
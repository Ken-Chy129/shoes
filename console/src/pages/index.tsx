import {Button, Card, Form, Input, message, Radio, Row, Select} from "antd";
import React, {useEffect} from "react";
import {doGetRequest, doPostRequest} from "@/util/http";
import {SETTING_API} from "@/services/shoes";

const SettingPage = () => {
    const [poisonForm] = Form.useForm();
    const [kcForm] = Form.useForm();
    const [stockxForm] = Form.useForm();

    useEffect(() => {
        doGetRequest(SETTING_API.POISON, {}, {
            onSuccess: res => {
                poisonForm.setFieldsValue(res.data);
            }
        });
        doGetRequest(SETTING_API.KC, {}, {
            onSuccess: res => {
                kcForm.setFieldsValue(res.data);
            }
        });
        doGetRequest(SETTING_API.QUERY_STOCKX_CONFIG, {}, {
            onSuccess: res => {
                stockxForm.setFieldsValue(res.data);
            }
        });
        queryToken();
        queryStockxSetting();
    }, []);

    const updatePoisonSetting = () => {
        const apiMode = poisonForm.getFieldValue("apiMode");
        const maxPrice = poisonForm.getFieldValue("maxPrice");
        const openImportDBData = poisonForm.getFieldValue("openImportDBData");
        const openNoPriceCache = poisonForm.getFieldValue("openNoPriceCache");
        const stopQueryPrice = poisonForm.getFieldValue("stopQueryPrice");
        const openAllThreeFive = poisonForm.getFieldValue("openAllThreeFive");
        const minProfit = poisonForm.getFieldValue("minProfit");
        const minThreeFiveProfit = poisonForm.getFieldValue("minThreeFiveProfit");
        doPostRequest(SETTING_API.POISON, {apiMode, maxPrice, openImportDBData, openNoPriceCache, stopQueryPrice, openAllThreeFive, minProfit, minThreeFiveProfit}, {
            onSuccess: _ => {
                message.success("修改成功").then(_ => {});
            }
        })
    }

    const updateKcSetting = () => {
        const exchangeRate = kcForm.getFieldValue("exchangeRate");
        const freight = kcForm.getFieldValue("freight");
        const kcGetRate = kcForm.getFieldValue("kcGetRate");
        const kcServiceFee = kcForm.getFieldValue("kcServiceFee")
        doPostRequest(SETTING_API.KC, {exchangeRate, freight, kcGetRate, kcServiceFee}, {
            onSuccess: _ => {
                message.success("修改成功").then(_ => {});
            }
        })
    }

    const authorize = () => {
        doGetRequest(SETTING_API.AUTHORIZE_URL, {}, {
            onSuccess: res => {
                window.open(res.data, '_blank');
            }
        })
    }

    const initToken = () => {
        doPostRequest(SETTING_API.INIT_TOKEN, {}, {
            onSuccess: _ => {
                message.success("初始化成功").then(_ => {});
                doGetRequest(SETTING_API.QUERY_STOCKX_CONFIG, {}, {
                    onSuccess: res => {
                        stockxForm.setFieldsValue(res.data);
                    }
                });
            }
        })
    }

    const refreshToken = () => {
        doPostRequest(SETTING_API.REFRESH_TOKEN, {}, {
            onSuccess: _ => {
                message.success("刷新成功").then(_ => {});
                doGetRequest(SETTING_API.QUERY_STOCKX_CONFIG, {}, {
                    onSuccess: res => {
                        stockxForm.setFieldsValue(res.data);
                    }
                });
            }
        })
    }

    const queryToken = () => {
        doGetRequest(SETTING_API.QUERY_TOKEN, {}, {
            onSuccess: res => {
                stockxForm.setFieldValue("accessToken", res.data);
            }
        })
    }

    const updateToken = () => {
        const accessToken = stockxForm.getFieldValue("accessToken");
        doPostRequest(SETTING_API.UPDATE_TOKEN, {accessToken}, {
            onSuccess: _ => {
                message.success("刷新成功").then(_ => {});
                queryToken();
            }
        })
    }

    const queryStockxSetting = () => {
        doGetRequest(SETTING_API.STOCKX, {}, {
            onSuccess: res => {
                stockxForm.setFieldValue("sortType", res.data.sortType);
                stockxForm.setFieldValue("priceType", res.data.priceType);
            }
        })
    }

    const updateStockxSetting = () => {
        const sortType = stockxForm.getFieldValue("sortType");
        const priceType = stockxForm.getFieldValue("priceType");
        doPostRequest(SETTING_API.STOCKX, {sortType, priceType}, {
            onSuccess: _ => {
                message.success("刷新成功").then(_ => {});
                queryStockxSetting();
            }
        })
    }

    return <>
        <Card title={"通用配置"}>
            <Form form={kcForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="exchangeRate" label="汇率">
                        <Input/>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 50}}>
                        <Button type="primary" htmlType="submit" onClick={updateKcSetting}>
                            修改
                        </Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"得物配置"}>
            <Form form={poisonForm}
                  style={{display: "flex", alignItems: "center", flexWrap: "wrap"}}>
                <div>
                    <Row>
                        <Form.Item name="maxPrice" label="最大价格限制">
                            <Input/>
                        </Form.Item>
                        <Form.Item name="openImportDBData" label="使用历史得物价格" style={{marginLeft: 20}}>
                            <Radio.Group
                                options={[
                                    { value: true, label: '是' },
                                    { value: false, label: '否' }
                                ]}
                            />
                        </Form.Item>
                        <Form.Item name="openNoPriceCache" label="开启无价货号缓存" style={{marginLeft: 20}}>
                            <Radio.Group
                                options={[
                                    { value: true, label: '是' },
                                    { value: false, label: '否' }
                                ]}
                            />
                        </Form.Item>
                        <Form.Item name="stopQueryPrice" label="开启得物自动查价" style={{marginLeft: 20}}>
                            <Radio.Group
                                options={[
                                    { value: true, label: '是' },
                                    { value: false, label: '否' }
                                ]}
                            />
                        </Form.Item>
                    </Row>
                    {/*<Form.Item name="apiMode" label="查价模式">*/}
                    {/*    <Select*/}
                    {/*        style={{width: 160}}*/}
                    {/*        placeholder="请选择字段"*/}
                    {/*        allowClear*/}
                    {/*        optionFilterProp="label"*/}
                    {/*        options={*/}
                    {/*            [*/}
                    {/*                {label: '实时查询', value: 0},*/}
                    {/*                {label: '缓存查询', value: 1},*/}
                    {/*                {label: '综合模式', value: 2}*/}
                    {/*            ]*/}
                    {/*        }*/}
                    {/*    />*/}
                    {/*</Form.Item>*/}
                    <Row>
                       <Form.Item name="openAllThreeFive" label="开启全量3.5">
                           <Radio.Group
                               options={[
                                   { value: true, label: '是' },
                                   { value: false, label: '否' }
                               ]}
                           />
                       </Form.Item>
                       <Form.Item name="minProfit" label="最小利润" style={{marginLeft: 20}}>
                           <Input/>
                       </Form.Item>
                       <Form.Item name="minThreeFiveProfit" label="3.5最小利润" style={{marginLeft: 20}}>
                           <Input/>
                       </Form.Item>
                       <Form.Item style={{marginLeft: 50}}>
                           <Button type="primary" htmlType="submit" onClick={updatePoisonSetting}>
                               修改
                           </Button>
                       </Form.Item>
                    </Row>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"kc配置"}>
            <Form form={kcForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="freight" label="运费">
                        <Input/>
                    </Form.Item>
                    <Form.Item name="kcGetRate" label="KC到手比例" style={{marginLeft: 20}}>
                        <Input/>
                    </Form.Item>
                    <Form.Item name="kcServiceFee" label="kc服务费" style={{marginLeft: 20}}>
                        <Input/>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 50}}>
                        <Button type="primary" htmlType="submit" onClick={updateKcSetting}>
                            修改
                        </Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"stockx配置"}>
            {/*<Form form={stockxForm}*/}
            {/*      style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>*/}
            {/*    <div style={{display: "flex"}}>*/}
            {/*        <Form.Item name="expireTime" label="令牌有效期">*/}
            {/*            <Input disabled={true}/>*/}
            {/*        </Form.Item>*/}
            {/*        <Form.Item style={{marginLeft: 50}}>*/}
            {/*            <Button type="primary" htmlType="submit" onClick={authorize}>*/}
            {/*                认证*/}
            {/*            </Button>*/}
            {/*        </Form.Item>*/}
            {/*        <Form.Item style={{marginLeft: 50}}>*/}
            {/*            <Button type="primary" htmlType="submit" onClick={initToken}>*/}
            {/*                初始化令牌*/}
            {/*            </Button>*/}
            {/*        </Form.Item>*/}
            {/*        <Form.Item style={{marginLeft: 50}}>*/}
            {/*            <Button type="primary" htmlType="submit" onClick={refreshToken}>*/}
            {/*                刷新令牌*/}
            {/*            </Button>*/}
            {/*        </Form.Item>*/}
            {/*    </div>*/}
            {/*</Form>*/}
            <Form form={stockxForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="accessToken" label="令牌">
                        <Input/>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 50}}>
                        <Button type="primary" htmlType="submit" onClick={updateToken}>
                            手动重置令牌
                        </Button>
                    </Form.Item>
                </div>
            </Form>
            <Form form={stockxForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="sortType" label="排序方式">
                        <Select
                            style={{width: 160}}
                            placeholder="请选择字段"
                            allowClear
                            optionFilterProp="label"
                            options={
                                [
                                    {label: '精选', value: "featured"},
                                    {label: '最受欢迎', value: "most-active"},
                                    {label: '新最低报价', value: "recent_asks"},
                                    {label: '新最高出价', value: "recent_bids"},
                                    {label: '平均成交价', value: "average_deadstock_price"},
                                    {label: '总销量', value: "deadstock_sold"},
                                    {label: '价格波动性', value: "volatility"},
                                    {label: '溢价', value: "price_premium"},
                                    {label: '最新售价', value: "last_sale"},
                                    {label: '最低报价', value: "lowest_ask"},
                                    {label: '最高出价', value: "highest_bid"},
                                    {label: '发布日期', value: "release_date"},
                                ]
                            }
                        />
                    </Form.Item>
                    <Form.Item name="priceType" style={{marginLeft: 50}} label="价格类型">
                        <Select
                            style={{width: 160}}
                            placeholder="请选择字段"
                            allowClear
                            optionFilterProp="label"
                            options={
                                [
                                    {label: '更快售出', value: "sellFaster"},
                                    {label: '挣得更多', value: "earnMore"},
                                    {label: '立即售出', value: "sellNow"}
                                ]
                            }
                        />
                    </Form.Item>
                    <Form.Item style={{marginLeft: 50}}>
                        <Button type="primary" htmlType="submit" onClick={updateStockxSetting}>
                            更新配置
                        </Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
    </>
}

export default SettingPage;
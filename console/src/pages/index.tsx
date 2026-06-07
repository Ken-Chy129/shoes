import {Button, Card, Form, Input, InputNumber, message, Modal, Radio, Row, Select, Switch, Table} from "antd";
import React, {useEffect, useState} from "react";
import {doGetRequest, doPostRequest, doDeleteRequest, doPutRequest} from "@/util/http";
import {SETTING_API} from "@/services/shoes";

const SettingPage = () => {
    const [poisonForm] = Form.useForm();
    const [kcForm] = Form.useForm();
    const [kcTokenForm] = Form.useForm();
    const [stockxForm] = Form.useForm();

    // StockX 多账号
    const [stockxAccounts, setStockxAccounts] = useState<any[]>([]);
    const [accountModalVisible, setAccountModalVisible] = useState(false);
    const [editingAccount, setEditingAccount] = useState<any>(null);
    const [accountForm] = Form.useForm();

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
        queryKcToken();
        loadAccounts();
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

    const queryKcToken = () => {
        doGetRequest(SETTING_API.QUERY_KC_TOKEN, {}, {
            onSuccess: res => {
                kcTokenForm.setFieldValue("accessToken", res.data);
            }
        })
    }

    const updateKcToken = () => {
        const accessToken = kcTokenForm.getFieldValue("accessToken");
        doPostRequest(SETTING_API.UPDATE_KC_TOKEN, {accessToken}, {
            onSuccess: _ => {
                message.success("设置成功").then(_ => {});
                queryKcToken();
            }
        })
    }

    // ==================== StockX 多账号管理 ====================

    const loadAccounts = () => {
        doGetRequest(SETTING_API.STOCKX_ACCOUNTS, {}, {
            onSuccess: res => setStockxAccounts(res.data || [])
        });
    }

    const handleAddAccount = () => {
        setEditingAccount(null);
        accountForm.resetFields();
        setAccountModalVisible(true);
    }

    const handleEditAccount = (record: any) => {
        setEditingAccount(record);
        accountForm.setFieldsValue(record);
        setAccountModalVisible(true);
    }

    const handleDeleteAccount = (name: string) => {
        doDeleteRequest(`${SETTING_API.STOCKX_ACCOUNTS}/${name}`, {}, {
            onSuccess: () => {
                message.success('已删除');
                loadAccounts();
            }
        });
    }

    const handleAccountSubmit = () => {
        accountForm.validateFields().then(values => {
            if (editingAccount) {
                doPutRequest(`${SETTING_API.STOCKX_ACCOUNTS}/${editingAccount.name}`, values, {
                    onSuccess: () => {
                        message.success('已更新');
                        setAccountModalVisible(false);
                        loadAccounts();
                    }
                });
            } else {
                doPostRequest(SETTING_API.STOCKX_ACCOUNTS, values, {
                    onSuccess: () => {
                        message.success('已添加');
                        setAccountModalVisible(false);
                        loadAccounts();
                    }
                });
            }
        });
    }

    const handleToggleAccount = (record: any, enabled: boolean) => {
        doPutRequest(`${SETTING_API.STOCKX_ACCOUNTS}/${record.name}`, {...record, enabled}, {
            onSuccess: () => loadAccounts()
        });
    }

    const maskStr = (s: string) => s ? s.substring(0, 10) + '...' : '';

    const accountColumns = [
        {title: '账号名', dataIndex: 'name', key: 'name', width: 100},
        {title: '区域', dataIndex: 'country', key: 'country', width: 60},
        {title: '转账费率', dataIndex: 'transferFeeRate', key: 'transferFeeRate', width: 80, render: (v: number) => v === 0 ? '免' : `${(v * 100).toFixed(0)}%`},
        {title: '商家费率', dataIndex: 'merchantFeeRate', key: 'merchantFeeRate', width: 80, render: (v: number) => v === 0 ? '免' : `${(v * 100).toFixed(0)}%`},
        {title: '最低商家费', dataIndex: 'minMerchantFee', key: 'minMerchantFee', width: 90, render: (v: number) => `$${v}`},
        {title: '平台运费', dataIndex: 'platformShippingFee', key: 'platformShippingFee', width: 80, render: (v: number) => `$${v}`},
        {title: '运费(¥)', dataIndex: 'freight', key: 'freight', width: 70, render: (v: number) => `¥${v}`},
        {title: '启用', dataIndex: 'enabled', key: 'enabled', width: 60,
            render: (v: boolean, record: any) => (
                <Switch checked={v} onChange={(checked) => handleToggleAccount(record, checked)} size="small"/>
            )},
        {title: '操作', key: 'action', width: 120,
            render: (_: any, record: any) => (
                <span>
                    <Button type="link" size="small" onClick={() => handleEditAccount(record)}>编辑</Button>
                    <Button type="link" size="small" danger onClick={() => handleDeleteAccount(record.name)}>删除</Button>
                </span>
            )},
    ];

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
            <Form form={kcTokenForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="accessToken" label="令牌">
                        <Input/>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 50}}>
                        <Button type="primary" htmlType="submit" onClick={updateKcToken}>
                            手动设置令牌
                        </Button>
                    </Form.Item>
                </div>
            </Form>
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
        <Card title={"StockX 账号管理"} extra={<Button type="primary" size="small" onClick={handleAddAccount}>添加账号</Button>}>
            <Table dataSource={stockxAccounts} columns={accountColumns} rowKey="name" size="small" pagination={false}/>
        </Card>

        <Modal title={editingAccount ? '编辑账号' : '添加账号'} open={accountModalVisible}
               onOk={handleAccountSubmit} onCancel={() => setAccountModalVisible(false)}>
            <Form form={accountForm} layout="vertical">
                <Form.Item name="name" label="账号名" rules={[{required: true}]}
                           extra="唯一标识，不可重复">
                    <Input disabled={!!editingAccount}/>
                </Form.Item>
                <Form.Item name="country" label="区域" rules={[{required: true}]} initialValue="US">
                    <Select>
                        <Select.Option value="US">美区 (US)</Select.Option>
                        <Select.Option value="HK">港区 (HK)</Select.Option>
                    </Select>
                </Form.Item>
                <Form.Item name="apiKey" label="API Key" rules={[{required: true}]}>
                    <Input.TextArea rows={2}/>
                </Form.Item>
                <Form.Item name="authorization" label="Authorization (Bearer token)" rules={[{required: true}]}>
                    <Input.TextArea rows={3}/>
                </Form.Item>
                <Form.Item name="transferFeeRate" label="转账手续费比例" initialValue={0.03}
                           extra="设为0表示免手续费">
                    <InputNumber min={0} max={1} step={0.01} style={{width: '100%'}}/>
                </Form.Item>
                <Form.Item name="merchantFeeRate" label="商家手续费比例" initialValue={0.07}
                           extra="设为0表示免手续费">
                    <InputNumber min={0} max={1} step={0.01} style={{width: '100%'}}/>
                </Form.Item>
                <Form.Item name="minMerchantFee" label="最低商家手续费($)" initialValue={5.79}
                           extra="商家手续费不低于此值（比例为0时忽略）">
                    <InputNumber min={0} step={0.01} style={{width: '100%'}}/>
                </Form.Item>
                <Form.Item name="platformShippingFee" label="平台运费($)" rules={[{required: true}]}
                           extra="StockX平台收取的运费(USD)">
                    <InputNumber min={0} step={0.01} style={{width: '100%'}}/>
                </Form.Item>
                <Form.Item name="freight" label="人民币运费(¥)" initialValue={25}>
                    <InputNumber min={0} step={1} style={{width: '100%'}}/>
                </Form.Item>
                <Form.Item name="enabled" label="启用" valuePropName="checked" initialValue={true}>
                    <Switch/>
                </Form.Item>
            </Form>
        </Modal>

        <br/>
        <Card title={"StockX 通用配置"}>
            <Form form={stockxForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="accessToken" label="默认令牌（旧压价用）">
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
import {Button, Card, Form, Input, InputNumber, message, Modal, Radio, Row, Select, Steps, Switch, Table} from "antd";
import React, {useEffect, useState} from "react";
import {doGetRequest, doPostRequest, doDeleteRequest, doPutRequest} from "@/util/http";
import {SETTING_API} from "@/services/shoes";

const SettingPage = () => {
    const [poisonForm] = Form.useForm();
    const [kcForm] = Form.useForm();
    const [kcTokenForm] = Form.useForm();
    // StockX 多账号
    const [stockxAccounts, setStockxAccounts] = useState<any[]>([]);
    const [accountModalVisible, setAccountModalVisible] = useState(false);
    const [editingAccount, setEditingAccount] = useState<any>(null);
    const [accountForm] = Form.useForm();
    const [accountStep, setAccountStep] = useState(0);

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
        setAccountStep(0);
        setAccountModalVisible(true);
    }

    const handleEditAccount = (record: any) => {
        setEditingAccount(record);
        accountForm.setFieldsValue(record);
        setAccountStep(0);
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

    const accountColumns = [
        {title: '账号名', dataIndex: 'name', key: 'name', width: 100},
        {title: '区域', dataIndex: 'country', key: 'country', width: 60},
        {title: '转账费率', dataIndex: 'transferFeeRate', key: 'transferFeeRate', width: 80, render: (v: number) => v === 0 ? '免' : `${(v * 100).toFixed(0)}%`},
        {title: '商家费率', dataIndex: 'merchantFeeRate', key: 'merchantFeeRate', width: 80, render: (v: number) => v === 0 ? '免' : `${(v * 100).toFixed(0)}%`},
        {title: '最低商家费', dataIndex: 'minMerchantFee', key: 'minMerchantFee', width: 90, render: (v: number) => `$${v}`},
        {title: '平台运费', dataIndex: 'platformShippingFee', key: 'platformShippingFee', width: 80, render: (v: number) => `$${v}`},
        {title: '运费(¥)', dataIndex: 'freight', key: 'freight', width: 70, render: (v: number) => `¥${v}`},
        {title: '最小利润', dataIndex: 'minProfit', key: 'minProfit', width: 80, render: (v: number) => `¥${v}`},
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
               onCancel={() => setAccountModalVisible(false)} width={560}
               footer={[
                   accountStep > 0 && <Button key="prev" onClick={() => setAccountStep(s => s - 1)}>上一步</Button>,
                   accountStep < 2 && <Button key="next" type="primary" onClick={() => {
                       const fields = accountStep === 0
                           ? ['name', 'country', 'apiKey', 'authorization', 'enabled']
                           : ['transferFeeRate', 'merchantFeeRate', 'minMerchantFee', 'platformShippingFee', 'freight', 'minProfit'];
                       accountForm.validateFields(fields).then(() => setAccountStep(s => s + 1));
                   }}>下一步</Button>,
                   accountStep === 2 && <Button key="submit" type="primary" onClick={handleAccountSubmit}>提交</Button>,
               ]}>
            <Steps current={accountStep} size="small" style={{marginBottom: 24}}
                   items={[{title: '基本信息'}, {title: '费率配置'}, {title: '限流配置'}]}/>
            <Form form={accountForm} layout="vertical">
                <div style={{display: accountStep === 0 ? 'block' : 'none'}}>
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
                    <Form.Item name="enabled" label="启用" valuePropName="checked" initialValue={true}>
                        <Switch/>
                    </Form.Item>
                </div>
                <div style={{display: accountStep === 1 ? 'block' : 'none'}}>
                    <Form.Item name="transferFeeRate" label="转账手续费比例" initialValue={0.03}
                               extra="设为0表示免手续费">
                        <InputNumber min={0} max={1} step={0.01} style={{width: '100%'}}/>
                    </Form.Item>
                    <Form.Item name="merchantFeeRate" label="商家手续费比例" initialValue={0.07}
                               extra="设为0表示免手续费">
                        <InputNumber min={0} max={1} step={0.01} style={{width: '100%'}}/>
                    </Form.Item>
                    <Form.Item name="minMerchantFee" label="最低商家手续费($)" initialValue={5.79}
                               extra="商家手续费不低于此值；设为0表示免商家手续费">
                        <InputNumber min={0} step={0.01} style={{width: '100%'}}/>
                    </Form.Item>
                    <Form.Item name="platformShippingFee" label="平台运费($)" rules={[{required: true}]}
                               extra="StockX平台收取的运费(USD)">
                        <InputNumber min={0} step={0.01} style={{width: '100%'}}/>
                    </Form.Item>
                    <Form.Item name="freight" label="人民币运费(¥)" initialValue={25}>
                        <InputNumber min={0} step={1} style={{width: '100%'}}/>
                    </Form.Item>
                    <Form.Item name="minProfit" label="最小利润(¥)" initialValue={-30}
                               extra="低于此利润的商品会被加价$100">
                        <InputNumber step={1} style={{width: '100%'}}/>
                    </Form.Item>
                </div>
                <div style={{display: accountStep === 2 ? 'block' : 'none'}}>
                    <Form.Item name="graphqlQps" label="GraphQL QPS" initialValue={1}
                               extra="GraphQL接口每秒请求数（搜索、查在售等）">
                        <InputNumber min={0.1} max={10} step={0.1} style={{width: '100%'}}/>
                    </Form.Item>
                    <Form.Item name="apiQps" label="REST API QPS" initialValue={1}
                               extra="官方REST API每秒请求数（批量压价、查状态等）">
                        <InputNumber min={0.1} max={10} step={0.1} style={{width: '100%'}}/>
                    </Form.Item>
                    <Form.Item name="batchItemLimit" label="Batch Items上限 / 5分钟" initialValue={500}
                               extra="5分钟内批量提交的最大商品条数">
                        <InputNumber min={10} max={5000} step={10} style={{width: '100%'}}/>
                    </Form.Item>
                </div>
            </Form>
        </Modal>

    </>
}

export default SettingPage;
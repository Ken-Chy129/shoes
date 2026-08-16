import {Alert, Badge, Button, Card, Form, Input, InputNumber, message, Modal, Radio, Row, Select, Steps, Switch, Table, Tabs, Tag, Tooltip} from "antd";
import {ClockCircleOutlined, ExclamationCircleOutlined} from "@ant-design/icons";
import React, {useEffect, useState} from "react";
import {doGetRequest, doPostRequest, doDeleteRequest, doPutRequest} from "@/util/http";
import {SETTING_API} from "@/services/shoes";

// 解码 StockX bearer token(JWT)的 iat(签发=上次刷新)与 exp(过期)时间戳，单位秒。
// 纯前端解码，无需后端改动。
function decodeJwtTimes(auth?: string): {iat: number | null; exp: number | null} {
    const empty = {iat: null, exp: null};
    if (!auth) return empty;
    const jwt = auth.replace(/^Bearer\s+/i, '').trim();
    const parts = jwt.split('.');
    if (parts.length !== 3) return empty;
    try {
        let b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
        while (b64.length % 4) b64 += '=';
        const payload = JSON.parse(atob(b64));
        return {
            iat: typeof payload.iat === 'number' ? payload.iat : null,
            exp: typeof payload.exp === 'number' ? payload.exp : null,
        };
    } catch {
        return empty;
    }
}

const fmtTime = (sec: number) => new Date(sec * 1000).toLocaleString('zh-CN', {hour12: false});

// 相对时间（如“3分钟前”），用于"上次刷新"列，精确时刻放 tooltip。
const relTime = (sec: number) => {
    const diff = Date.now() / 1000 - sec;
    if (diff < 0) return '刚刚';
    if (diff < 60) return '刚刚';
    if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`;
    if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`;
    return `${Math.floor(diff / 86400)} 天前`;
};

const FEE_FIELD_NAMES = [
    'transferFeeRate', 'merchantFeeRate', 'minMerchantFee',
    'platformShippingFee', 'freight', 'minProfit',
];

const FeeProfileFields = ({prefix}: {prefix?: string}) => {
    const name = (field: string) => prefix ? [prefix, field] : field;
    const initialValue = (value: number) => prefix ? undefined : value;
    return <>
        <Form.Item name={name('transferFeeRate')} label="转账手续费比例" initialValue={initialValue(0.03)}
                   rules={[{required: true, message: '请输入转账手续费比例'}]} extra="设为0表示免手续费">
            <InputNumber min={0} max={1} step={0.01} style={{width: '100%'}}/>
        </Form.Item>
        <Form.Item name={name('merchantFeeRate')} label="商家手续费比例" initialValue={initialValue(0.07)}
                   rules={[{required: true, message: '请输入商家手续费比例'}]} extra="设为0表示免手续费">
            <InputNumber min={0} max={1} step={0.01} style={{width: '100%'}}/>
        </Form.Item>
        <Form.Item name={name('minMerchantFee')} label="最低商家手续费($)" initialValue={initialValue(5.79)}
                   rules={[{required: true, message: '请输入最低商家手续费'}]}
                   extra="商家手续费不低于此值；设为0表示免商家手续费">
            <InputNumber min={0} step={0.01} style={{width: '100%'}}/>
        </Form.Item>
        <Form.Item name={name('platformShippingFee')} label="平台运费($)" initialValue={initialValue(0)}
                   rules={[{required: true, message: '请输入平台运费'}]} extra="StockX平台收取的运费(USD)">
            <InputNumber min={0} step={0.01} style={{width: '100%'}}/>
        </Form.Item>
        <Form.Item name={name('freight')} label="人民币运费(¥)" initialValue={initialValue(25)}
                   rules={[{required: true, message: '请输入人民币运费'}]}>
            <InputNumber min={0} step={1} style={{width: '100%'}}/>
        </Form.Item>
        <Form.Item name={name('minProfit')} label="最小利润(¥)" initialValue={initialValue(-30)}
                   rules={[{required: true, message: '请输入最小利润'}]}
                   extra="低于此利润的商品按任务设置加价或下架">
            <InputNumber step={1} style={{width: '100%'}}/>
        </Form.Item>
    </>;
};

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
    const inheritSpecialStyleFee = Form.useWatch('inheritSpecialStyleFee', accountForm) ?? true;
    const inheritSpecialStyle35Fee = Form.useWatch('inheritSpecialStyle35Fee', accountForm) ?? true;

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
        accountForm.setFieldsValue({
            inheritSpecialStyleFee: true,
            inheritSpecialStyle35Fee: true,
            autoReplenishmentEnabled: false,
        });
        setAccountStep(0);
        setAccountModalVisible(true);
    }

    const handleEditAccount = (record: any) => {
        setEditingAccount(record);
        accountForm.resetFields();
        accountForm.setFieldsValue({
            ...record,
            inheritSpecialStyleFee: !record.specialStyleFeeConfig,
            inheritSpecialStyle35Fee: !record.specialStyle35FeeConfig,
        });
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
            const accountValues = {...values};
            delete accountValues.inheritSpecialStyleFee;
            delete accountValues.inheritSpecialStyle35Fee;
            if (values.inheritSpecialStyleFee) accountValues.specialStyleFeeConfig = null;
            if (values.inheritSpecialStyle35Fee) accountValues.specialStyle35FeeConfig = null;
            if (editingAccount) {
                // 合并原账号字段：autoRefresh 等无对应 Form.Item 的字段不在 values 里，
                // 直接 PUT values 会把它们丢成默认值(如自动刷新被清成"手动")，故先展开原对象再覆盖。
                doPutRequest(`${SETTING_API.STOCKX_ACCOUNTS}/${editingAccount.name}`, {...editingAccount, ...accountValues}, {
                    onSuccess: () => {
                        message.success('已更新');
                        setAccountModalVisible(false);
                        loadAccounts();
                    }
                });
            } else {
                doPostRequest(SETTING_API.STOCKX_ACCOUNTS, accountValues, {
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

    const handleToggleAutoReplenishment = (record: any, autoReplenishmentEnabled: boolean) => {
        doPutRequest(`${SETTING_API.STOCKX_ACCOUNTS}/${record.name}`, {...record, autoReplenishmentEnabled}, {
            onSuccess: () => loadAccounts()
        });
    }

    const enableSpecialFeeProfile = (prefix: string) => {
        if (accountForm.getFieldValue(prefix)) return;
        const defaults = accountForm.getFieldsValue(FEE_FIELD_NAMES);
        accountForm.setFieldValue(prefix, defaults);
    };

    const accountColumns = [
        {title: '账号名', dataIndex: 'name', key: 'name', width: 100},
        {title: '区域', dataIndex: 'country', key: 'country', width: 60},
        {title: '转账费率', dataIndex: 'transferFeeRate', key: 'transferFeeRate', width: 80, render: (v: number) => v === 0 ? '免' : `${(v * 100).toFixed(0)}%`},
        {title: '商家费率', dataIndex: 'merchantFeeRate', key: 'merchantFeeRate', width: 80, render: (v: number) => v === 0 ? '免' : `${(v * 100).toFixed(0)}%`},
        {title: '最低商家费', dataIndex: 'minMerchantFee', key: 'minMerchantFee', width: 90, render: (v: number) => `$${v}`},
        {title: '平台运费', dataIndex: 'platformShippingFee', key: 'platformShippingFee', width: 80, render: (v: number) => `$${v}`},
        {title: '运费(¥)', dataIndex: 'freight', key: 'freight', width: 70, render: (v: number) => `¥${v}`},
        {title: '最小利润', dataIndex: 'minProfit', key: 'minProfit', width: 80, render: (v: number) => `¥${v}`},
        {title: '特殊费率', key: 'specialFees', width: 130, render: (_: any, record: any) => <>
            <Tag color={record.specialStyleFeeConfig ? 'blue' : 'default'}>得物</Tag>
            <Tag color={record.specialStyle35FeeConfig ? 'purple' : 'default'}>得物3.5</Tag>
        </>},
        {title: '上次刷新', dataIndex: 'authorization', key: 'tokenIat', width: 110,
            render: (auth: string) => {
                const {iat} = decodeJwtTimes(auth);
                if (!iat) return <span style={{color: '#bfbfbf'}}>—</span>;
                return <Tooltip title={fmtTime(iat)}>
                    <span style={{color: '#8c8c8c', fontSize: 13}}>{relTime(iat)}</span>
                </Tooltip>;
            }},
        {title: 'Token到期', dataIndex: 'authorization', key: 'tokenExp', width: 120,
            render: (auth: string) => {
                const {exp} = decodeJwtTimes(auth);
                if (!exp) return <Tag bordered={false} color="default">无</Tag>;
                const leftMs = exp * 1000 - Date.now();
                if (leftMs <= 0) return <Tooltip title={fmtTime(exp)}>
                    <Tag bordered={false} color="error" icon={<ExclamationCircleOutlined/>}>已过期</Tag>
                </Tooltip>;
                const hours = leftMs / 3600000;
                const left = hours < 1 ? `${Math.round(leftMs / 60000)} 分钟` : `${hours.toFixed(1)} 小时`;
                const color = hours < 2 ? 'warning' : 'success';
                return <Tooltip title={`到期 ${fmtTime(exp)}`}>
                    <Tag bordered={false} color={color} icon={<ClockCircleOutlined/>}>{left}</Tag>
                </Tooltip>;
            }},
        {title: '自动刷新', dataIndex: 'autoRefresh', key: 'autoRefresh', width: 90,
            render: (v: boolean) => v
                ? <Badge status="success" text={<span style={{color: '#52c41a', fontSize: 13}}>自动</span>}/>
                : <Badge status="default" text={<span style={{color: '#bfbfbf', fontSize: 13}}>手动</span>}/>},
        {title: '自动补单', dataIndex: 'autoReplenishmentEnabled', key: 'autoReplenishmentEnabled', width: 90,
            render: (v: boolean, record: any) => (
                <Tooltip title="开启后每12小时自动补单；账号停用时不会执行">
                    <Switch checked={!!v}
                            onChange={(checked) => handleToggleAutoReplenishment(record, checked)}
                            size="small"/>
                </Tooltip>
            )},
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
               onCancel={() => setAccountModalVisible(false)} width={680}
               footer={[
                   accountStep > 0 && <Button key="prev" onClick={() => setAccountStep(s => s - 1)}>上一步</Button>,
                   accountStep < 2 && <Button key="next" type="primary" onClick={() => {
                       const fields: any[] = accountStep === 0
                           ? ['name', 'country', 'apiKey', 'authorization', 'enabled', 'autoReplenishmentEnabled']
                           : [...FEE_FIELD_NAMES];
                       if (accountStep === 1 && !inheritSpecialStyleFee) {
                           fields.push(...FEE_FIELD_NAMES.map(field => ['specialStyleFeeConfig', field]));
                       }
                       if (accountStep === 1 && !inheritSpecialStyle35Fee) {
                           fields.push(...FEE_FIELD_NAMES.map(field => ['specialStyle35FeeConfig', field]));
                       }
                       accountForm.validateFields(fields).then(() => setAccountStep(s => s + 1));
                   }}>下一步</Button>,
                   accountStep === 2 && <Button key="submit" type="primary" onClick={handleAccountSubmit}>提交</Button>,
               ]}>
            <Steps current={accountStep} size="small" style={{marginBottom: 24}}
                   items={[{title: '基本信息'}, {title: '费率配置'}, {title: '请求策略'}]}/>
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
                    <Form.Item name="autoReplenishmentEnabled" label="自动补单" valuePropName="checked"
                               initialValue={false}
                               extra="开启后每12小时自动补单；仅账号处于启用状态时执行">
                        <Switch/>
                    </Form.Item>
                </div>
                <div style={{display: accountStep === 1 ? 'block' : 'none'}}>
                    <Tabs items={[
                        {
                            key: 'default', label: '默认费率',
                            children: <>
                                <Alert type="info" showIcon style={{marginBottom: 16}}
                                       message="默认任务及Excel中“默认”类型使用这套配置"/>
                                <FeeProfileFields/>
                            </>,
                        },
                        {
                            key: 'poison', label: '特殊货号',
                            children: <>
                                <Form.Item name="inheritSpecialStyleFee" label="费率来源" valuePropName="checked">
                                    <Switch checkedChildren="跟随默认" unCheckedChildren="独立配置"
                                            onChange={checked => { if (!checked) enableSpecialFeeProfile('specialStyleFeeConfig'); }}/>
                                </Form.Item>
                                {inheritSpecialStyleFee
                                    ? <Alert type="info" showIcon message="当前跟随默认费率配置"/>
                                    : <FeeProfileFields prefix="specialStyleFeeConfig"/>}
                            </>,
                        },
                        {
                            key: 'poison35', label: '特殊货号3.5',
                            children: <>
                                <Alert type="info" showIcon style={{marginBottom: 16}}
                                       message="得物3.5价格 = 得物价格 × 0.95 - 48（取整）"/>
                                <Form.Item name="inheritSpecialStyle35Fee" label="费率来源" valuePropName="checked">
                                    <Switch checkedChildren="跟随默认" unCheckedChildren="独立配置"
                                            onChange={checked => { if (!checked) enableSpecialFeeProfile('specialStyle35FeeConfig'); }}/>
                                </Form.Item>
                                {inheritSpecialStyle35Fee
                                    ? <Alert type="info" showIcon message="当前跟随默认费率配置"/>
                                    : <FeeProfileFields prefix="specialStyle35FeeConfig"/>}
                            </>,
                        },
                    ]}/>
                </div>
                <div style={{display: accountStep === 2 ? 'block' : 'none'}}>
                    <Alert type="info" showIcon
                           message="StockX请求固定按账号每秒1次"
                           description="REST与GraphQL共享限速；不再本地主动限制5分钟批量条数，以StockX真实429响应切换Bulk/Single通道。"/>
                </div>
            </Form>
        </Modal>

    </>
}

export default SettingPage;

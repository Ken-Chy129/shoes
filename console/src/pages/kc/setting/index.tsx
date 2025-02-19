import {
    Button, Card, DatePicker,
    Form,
    Input,
    message,
    Modal,
    Radio,
    Select,
    Space,
    Table,
    Tabs,
    Tooltip
} from "antd";
import React, {useEffect, useState} from "react";
import {doDeleteRequest, doGetRequest, doPostRequest} from "@/util/http";
import {TEMPLATE_API} from "@/services/management";
import {FieldSelect, MachineSelect, NamespaceSelect} from "@/components";
import {SETTING_API} from "@/services/shoes";

const SettingPage = () => {
    const [settingForm] = Form.useForm();
    const [conditionForm] = Form.useForm();

    const [brandSettings, setBrandSettings] = useState<[]>([]);
    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [total, setTotal] = useState(0);

    useEffect(() => {
        doGetRequest(SETTING_API.QUERY_PRICE_SETTING, {}, {
            onSuccess: res => {
                console.log(res)
                settingForm.setFieldsValue(res.data);
            }
        });
        queryBrandSetting();
    }, []);

    const updatePriceSetting = () => {
        const exchangeRate = settingForm.getFieldValue("exchangeRate");
        const freight = settingForm.getFieldValue("freight");
        const platformRate = settingForm.getFieldValue("platformRate");
        const minProfitRate = settingForm.getFieldValue("minProfitRate");
        const minProfit = settingForm.getFieldValue("minProfit");
        const priceType = settingForm.getFieldValue("priceType");
        console.log(priceType)
        doPostRequest(SETTING_API.UPDATE_PRICE_SETTING, {exchangeRate, freight, platformRate, minProfitRate, minProfit, priceType}, {
            onSuccess: _ => {
                message.success("修改成功").then(_ => {});
            }
        })
    }

    const queryBrandSetting = () => {
        const name = conditionForm.getFieldValue("name");
        const needCrawl = conditionForm.getFieldValue("needCrawl");
        doGetRequest(SETTING_API.QUERY_BRAND_SETTING, {name, needCrawl, pageIndex, pageSize}, {
            onSuccess: res => {
                res.data.forEach((brandSetting: any) => {
                    brandSetting.needCrawlText = brandSetting.needCrawl ? "需要爬取" : "已关闭爬取";
                })
                setBrandSettings(res.data);
            }
        });
    }

    const updateBrandSetting = (brandSetting: any) => {
        doPostRequest(SETTING_API.UPDATE_BRAND_SETTING, brandSetting, {
            onSuccess: _ => {
                message.success("修改成功").then();
                queryBrandSetting();
            }
        });
    }

    const columns = [
        {
            title: '名称',
            dataIndex: 'name',
            key: 'name',
            width: '20%'
        },
        {
            title: '商品总数',
            dataIndex: 'total',
            key: 'total',
            width: '15%', // 设置列宽为30%
        },
        {
            title: '爬取货号数量',
            dataIndex: 'crawlCnt',
            key: 'crawlCnt',
            width: '15%', // 设置列宽为30%
        },
        {
            title: '是否爬取',
            dataIndex: 'needCrawlText',
            key: 'needCrawlText',
            width: '20%', // 设置列宽为30%
        },
        {
            title: '操作',
            key: 'action',
            render: (text: string, brandSetting: { name: string, needCrawl: boolean }) => (
                <span>
                  <Button onClick={() => {updateBrandSetting(
                      {
                          name: brandSetting.name,
                          needCrawl: !brandSetting.needCrawl
                      }
                  )}}>
                    {brandSetting.needCrawl ? '关闭爬取' : '开启爬取'}
                  </Button>
                  <Button style={{marginLeft: 20}} onClick={() => {}}>
                    修改爬取数量
                  </Button>
                </span>
            ),
            width: '30%', // 设置列宽为30%
        }
    ];

    return <>
        <Card title={"基本配置"}>
            <Form form={settingForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="exchangeRate" label="汇率">
                        <Input />
                    </Form.Item>
                    <Form.Item name="freight" label="运费" style={{marginLeft: 20}}>
                        <Input/>
                    </Form.Item>
                    <Form.Item name="minProfit" label="最小利润" style={{marginLeft: 20}}>
                        <Input/>
                    </Form.Item>
                    <Form.Item name="priceType" label="价格类型" style={{marginLeft: 20}}>
                        <Select
                            style={{width: 200}}
                            options={[
                                {label: '普通价格', value: 'normal'},
                                {label: '闪电价格', value: 'lightning'},
                            ]}
                        />
                    </Form.Item>
                    <Form.Item style={{marginLeft: 50}}>
                        <Button type="primary" htmlType="submit" onClick={updatePriceSetting}>
                            修改
                        </Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
        <Card title={"商品爬取配置"} style={{marginTop: 10}}>
            <Form form={conditionForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="name" label="品牌名称">
                        <Input />
                    </Form.Item>
                    <Form.Item name="needCrawl" label="是否爬取" style={{marginLeft: 20}}>
                        <Select
                            style={{width: 160}}
                            placeholder="请选择字段"
                            allowClear
                            optionFilterProp="label"
                            options={
                                [
                                    {label: '是', value: true},
                                    {label: '否', value: false},
                                ]
                            }
                        />
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" htmlType="submit" onClick={queryBrandSetting}>
                            查询
                        </Button>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" htmlType="reset" onClick={() => conditionForm.resetFields()}>
                            重置
                        </Button>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" htmlType="reset" onClick={() => conditionForm.resetFields()}>
                            指定必爬货号
                        </Button>
                    </Form.Item>
                </div>
            </Form>
            <Table
                columns={columns}
                dataSource={brandSettings}
                pagination={{
                    current: pageIndex,
                    pageSize: pageSize,
                    total: total,
                    showSizeChanger: true,
                    onChange: (current, pageSize) => {
                        setPageIndex(current);
                        setPageSize(pageSize);
                    }
                }}
                rowKey="id"
            />
        </Card>
    </>
}

export default SettingPage;
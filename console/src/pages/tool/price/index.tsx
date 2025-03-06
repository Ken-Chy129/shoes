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
import {PRICE_API, SETTING_API} from "@/services/shoes";

const PricePage = () => {
    const [conditionForm] = Form.useForm();
    const [crawlCntForm] = Form.useForm();

    const [priceList, setPriceList] = useState<[]>([]);

    const [showDefaultCntModifiedModal, setShowDefaultCntModifiedModal] = useState(false);

    useEffect(() => {
    }, []);


    const queryPriceByModel = () => {
        const modelNo = conditionForm.getFieldValue("modelNo");
        const mode = conditionForm.getFieldValue("mode") ?? "dbFirst";
        doGetRequest(PRICE_API.QUERY_BY_MODEL, {modelNo, mode}, {
            onSuccess: res => {
                setPriceList(res.data);
            },
            onError: _ => {
                setPriceList([]);
            }
        });
    }

    const updateBrandSetting = (brandSetting: any) => {
        doPostRequest(SETTING_API.UPDATE_BRAND_SETTING, brandSetting, {
            onSuccess: _ => {
                message.success("修改成功").then();
            }
        });
    }


    const columns = [
        {
            title: '尺码',
            dataIndex: 'size',
            key: 'size',
            width: '10%'
        },
        {
            title: '普通发货价',
            dataIndex: 'normalPrice',
            key: 'normalPrice',
            width: '10%', // 设置列宽为30%
        },
        {
            title: '闪电发货价',
            dataIndex: 'lightningPrice',
            key: 'lightningPrice',
            width: '10%', // 设置列宽为30%
        },
        {
            title: '极速发货价',
            dataIndex: 'fastPrice',
            key: 'fastPrice',
            width: '10%', // 设置列宽为30%
        },
        {
            title: '品牌直发',
            dataIndex: 'brandPrice',
            key: 'brandPrice',
            width: '10%', // 设置列宽为30%
        },
        {
            title: 'kc价格',
            dataIndex: 'kcPrice',
            key: 'kcPrice',
            width: '10%', // 设置列宽为30%
        },
        {
            title: 'kc盈利(-1)',
            dataIndex: 'kcEarn',
            key: 'kcEarn',
            width: '10%', // 设置列宽为30%
        },
        {
            title: '绿叉盈利(-1)',
            dataIndex: 'stockxEarn',
            key: 'stockxEarn',
            width: '10%', // 设置列宽为30%
        },
        {
            title: '操作',
            key: 'action',
            render: (text: string, brandSetting: { name: string, needCrawl: boolean }) => (
                <span>
                  <Button onClick={() => updateBrandSetting(
                      {
                          name: brandSetting.name,
                          needCrawl: !brandSetting.needCrawl
                      }
                  )}>
                    KC改价
                  </Button>
                  <Button style={{marginLeft: 20}} onClick={() => {
                      crawlCntForm.setFieldValue("name", brandSetting.name);
                  }}>
                    绿叉改价
                  </Button>
                </span>
            ),
            width: '30%', // 设置列宽为30%
        }
    ];

    return <>
        <Card title={"价格查询"} style={{marginTop: 10}}>
            <Form form={conditionForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="modelNo" label="货号">
                        <Input/>
                    </Form.Item>
                    <Form.Item name="mode" label="查询模式" style={{marginLeft: 20}}>
                        <Select
                            style={{width: 160}}
                            placeholder="请选择字段"
                            allowClear
                            optionFilterProp="label"
                            defaultValue={"dbFirst"}
                            options={
                                [
                                    {label: '数据库查询', value: "db"},
                                    {label: '实时查询', value: "realTime"},
                                    {label: '数据库优先', value: "dbFirst"},
                                ]
                            }
                        />
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" htmlType="submit" onClick={queryPriceByModel}>
                            查询
                        </Button>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" htmlType="reset" onClick={() => conditionForm.resetFields()}>
                            重置
                        </Button>
                    </Form.Item>
                </div>
                <div style={{display: "flex"}}>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button onClick={() => setShowDefaultCntModifiedModal(true)}>
                            一键改价（KC）
                        </Button>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button onClick={() => setShowDefaultCntModifiedModal(true)}>
                            一键改价（绿叉）
                        </Button>
                    </Form.Item>
                </div>
            </Form>
            <Table
                columns={columns}
                dataSource={priceList}
                rowKey="id"
            />
        </Card>

        {/*<Modal*/}
        {/*    title="设置上架利润条件"*/}
        {/*    open={showDefaultCntModifiedModal}*/}
        {/*    onOk={handleModalClose}*/}
        {/*    onCancel={handleModalClose}*/}
        {/*    footer={[*/}
        {/*        <Space>*/}
        {/*            <Button key="push" onClick={() => {*/}
        {/*                updateDefaultCrawlCntModelNos();*/}
        {/*                defaultCntForm.resetFields();*/}
        {/*                handleModalClose();*/}
        {/*            }}>*/}
        {/*                修改*/}
        {/*            </Button>*/}
        {/*            <Button key="close" onClick={handleModalClose}>*/}
        {/*                关闭*/}
        {/*            </Button>*/}
        {/*        </Space>*/}
        {/*    ]}*/}
        {/*>*/}
        {/*    <Form form={defaultCntForm} style={{margin: 30}}>*/}
        {/*        <Form.Item name={"defaultCnt"} label={"默认爬取数量"}>*/}
        {/*            <Input/>*/}
        {/*        </Form.Item>*/}
        {/*    </Form>*/}
        {/*</Modal>*/}
    </>
}

export default PricePage;
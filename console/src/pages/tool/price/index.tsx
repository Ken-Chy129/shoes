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

const PricePage = () => {
    const [conditionForm] = Form.useForm();
    const [crawlCntForm] = Form.useForm();
    const [mustCrawlForm] = Form.useForm();
    const [defaultCntForm] = Form.useForm();

    const [brandSettings, setBrandSettings] = useState<[]>([]);
    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [total, setTotal] = useState(0);

    const [showCrawlCntModifiedModal, setShowCrawlCntModifiedModal] = useState(false);
    const [showCrawlModelNoModifiedModal, setShowCrawlModelNoModifiedModal] = useState(false);
    const [showDefaultCntModifiedModal, setShowDefaultCntModifiedModal] = useState(false);

    useEffect(() => {
        queryBrandSetting();
    }, []);

    useEffect(() => {
        queryBrandSetting();
    }, [pageIndex, pageSize]);


    const queryBrandSetting = () => {
        const name = conditionForm.getFieldValue("name");
        const needCrawl = conditionForm.getFieldValue("needCrawl");
        doGetRequest(SETTING_API.QUERY_BRAND_SETTING, {name, needCrawl, pageIndex, pageSize}, {
            onSuccess: res => {
                res.data.forEach((brandSetting: any) => {
                    brandSetting.needCrawlText = brandSetting.needCrawl ? "需要爬取" : "已关闭爬取";
                })
                setBrandSettings(res.data);
                setTotal(res.total)
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
            title: '绿叉价格',
            dataIndex: 'stockxPrice',
            key: 'stockxPrice',
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
                    {brandSetting.needCrawl ? '暂停爬取' : '开启爬取'}
                  </Button>
                  <Button style={{marginLeft: 20}} onClick={() => {
                      setShowCrawlCntModifiedModal(true);
                      crawlCntForm.setFieldValue("name", brandSetting.name);
                  }}>
                    修改爬取数量
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
                </div>
                <div style={{display: "flex"}}>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button onClick={() => setShowDefaultCntModifiedModal(true)}>
                            指定默认爬取数量
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

        {/*<Modal*/}
        {/*    title="指定默认爬取数量"*/}
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
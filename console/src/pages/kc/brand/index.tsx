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
import {KC_API, SETTING_API} from "@/services/shoes";

const BrandPage = () => {
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

    const refreshKcItems = () => {
        doGetRequest(KC_API.REFRESH_ITEM, {}, {
            onSuccess: _ => {
                message.success("开始异步执行刷新").then();
            }
        });
    }

    const queryMustCrawlModelNos = () => {
        doGetRequest(SETTING_API.QUERY_MUST_CRAWL_MODEL_NOS, {}, {
            onSuccess: res => {
                mustCrawlForm.setFieldValue("mustCrawlModelNos", res.data);
            }
        });
    }

    const updateMustCrawlModelNos = () => {
        const modelNos = mustCrawlForm.getFieldValue("mustCrawlModelNos");
        doPostRequest(SETTING_API.UPDATE_MUST_CRAWL_MODEL_NOS, {modelNos}, {
            onSuccess: _ => {
                message.success("修改成功").then();
            }
        });
    }

    const updateDefaultCrawlCntModelNos = () => {
        const defaultCnt = defaultCntForm.getFieldValue("defaultCnt");
        doPostRequest(SETTING_API.UPDATE_DEFAULT_CRAWL_CNT, {defaultCnt}, {
            onSuccess: _ => {
                message.success("修改成功").then();
                queryBrandSetting();
            }
        });
    }

    const handleModalClose = () => {
        setShowCrawlCntModifiedModal(false);
        setShowCrawlModelNoModifiedModal(false);
        setShowDefaultCntModifiedModal(false);
        crawlCntForm.resetFields();
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
        <Card title={"品牌配置"} style={{marginTop: 10}}>
            <Form form={conditionForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="name" label="品牌名称">
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
                        <Button onClick={() => {
                            refreshKcItems();
                        }}>
                            刷新商品
                        </Button>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button onClick={() => {
                            queryMustCrawlModelNos();
                            setShowCrawlModelNoModifiedModal(true);
                        }}>
                            指定必爬货号
                        </Button>
                    </Form.Item>
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

        <Modal
            title="修改爬取数量"
            open={showCrawlCntModifiedModal}
            onOk={handleModalClose}
            onCancel={handleModalClose}
            footer={[
                <Space>
                    <Button key="push" onClick={() => {
                        updateBrandSetting({
                            name: crawlCntForm.getFieldValue("name"),
                            crawlCnt: crawlCntForm.getFieldValue("crawlCnt")
                        });
                        handleModalClose();
                    }}>
                        修改
                    </Button>
                    <Button key="close" onClick={handleModalClose}>
                        关闭
                    </Button>
                </Space>
            ]}
            style={{maxWidth: 600}}
        >
            <Form form={crawlCntForm} style={{margin: 30}}>
                <Form.Item name={"crawlCnt"} label={"爬取数量"}>
                    <Input.TextArea/>
                </Form.Item>
            </Form>
        </Modal>

        <Modal
            title="指定爬取货号"
            open={showCrawlModelNoModifiedModal}
            onOk={handleModalClose}
            onCancel={handleModalClose}
            footer={[
                <Space>
                    <Button key="push" onClick={() => {
                        updateMustCrawlModelNos();
                        handleModalClose();
                    }}>
                        修改
                    </Button>
                    <Button key="close" onClick={handleModalClose}>
                        关闭
                    </Button>
                </Space>
            ]}
            width={1000}
        >
            <Form form={mustCrawlForm} style={{margin: 30}}>
                <Form.Item name={"mustCrawlModelNos"} label={"爬取货号"}>
                    <Input.TextArea rows={25}/>
                </Form.Item>
            </Form>
        </Modal>

        <Modal
            title="指定默认爬取数量"
            open={showDefaultCntModifiedModal}
            onOk={handleModalClose}
            onCancel={handleModalClose}
            footer={[
                <Space>
                    <Button key="push" onClick={() => {
                        updateDefaultCrawlCntModelNos();
                        defaultCntForm.resetFields();
                        handleModalClose();
                    }}>
                        修改
                    </Button>
                    <Button key="close" onClick={handleModalClose}>
                        关闭
                    </Button>
                </Space>
            ]}
        >
            <Form form={defaultCntForm} style={{margin: 30}}>
                <Form.Item name={"defaultCnt"} label={"默认爬取数量"}>
                    <Input/>
                </Form.Item>
            </Form>
        </Modal>
    </>
}

export default BrandPage;
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
import {POISON_API, SETTING_API} from "@/services/shoes";

const PoisonPage = () => {
    const [poisonForm] = Form.useForm();
    const [threePointFiveModelNos, setThreePointFiveModelNos] = useState('');
    const [specialPriceModelNos, setSpecialPriceModelNos] = useState('');
    const threePointFiveType = 1;

    useEffect(() => {
        queryThreePointFiveModelNos();
        querySpecialPriceModelNos();
    }, []);

    const refreshPoisonPrice = (overwriteOld: boolean) => {
        doGetRequest(POISON_API.REFRESH_PRICE, {overwriteOld}, {
            onSuccess: _ => {
                message.success("开始执行异步刷新").then(_ => {});
            }
        })
    }

    const queryThreePointFiveModelNos = () => {
        doGetRequest(SETTING_API.QUERY_CUSTOM_MODEL_NOS, {type: threePointFiveType}, {
            onSuccess: res => {
                setThreePointFiveModelNos(res.data);
            }
        });
    }

    const updateThreePointFiveModelNos = () => {
        const modelNos = threePointFiveModelNos;
        doPostRequest(SETTING_API.UPDATE_CUSTOM_MODEL_NOS, {modelNos, type: threePointFiveType}, {
            onSuccess: _ => {
                message.success("修改成功").then();
            }
        });
    }

    const querySpecialPriceModelNos = () => {
        doGetRequest(SETTING_API.QUERY_SPECIAL_PRICE_MODEL_NOS, {}, {
            onSuccess: res => {
                setSpecialPriceModelNos(res.data);
            }
        });
    }

    const updateSpecialPriceModelNos = () => {
        const modelNos = specialPriceModelNos;
        doPostRequest(SETTING_API.UPDATE_SPECIAL_PRICE_MODEL_NOS, {modelNos}, {
            onSuccess: _ => {
                message.success("修改成功").then();
            }
        });
    }

    return <>
        {/*<Card title={"操作"}>*/}
        {/*    <Form form={poisonForm}*/}
        {/*          style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>*/}
        {/*        <div style={{display: "flex"}}>*/}
        {/*            <Form.Item>*/}
        {/*                <Button type="primary" htmlType="submit" onClick={() => refreshPoisonPrice(true)}>*/}
        {/*                    全量刷新得物价格*/}
        {/*                </Button>*/}
        {/*            </Form.Item>*/}
        {/*            <Form.Item style={{marginLeft: 30}}>*/}
        {/*                <Button type="primary" htmlType="submit" onClick={() => refreshPoisonPrice(false)}>*/}
        {/*                    增量刷新得物价格*/}
        {/*                </Button>*/}
        {/*            </Form.Item>*/}
        {/*        </div>*/}
        {/*    </Form>*/}
        {/*</Card>*/}
        {/*<br/>*/}
        <Card title={"使用3.5的货号"} >
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch' }}>
                <Input.TextArea rows={15} value={threePointFiveModelNos} onChange={e => setThreePointFiveModelNos(e.target.value)} />

                {/* 按钮容器，使用 flex-end 实现右对齐 */}
                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 10 }}>
                    <Button
                        key="push"
                        onClick={updateThreePointFiveModelNos}
                    >
                        修改
                    </Button>
                </div>
            </div>
        </Card>
        <br/>
        <Card title={"指定价格"} style={{ marginTop: 10 }}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch' }}>
                <Input.TextArea rows={15} value={specialPriceModelNos} onChange={e => setSpecialPriceModelNos(e.target.value)} />

                {/* 按钮容器，使用 flex-end 实现右对齐 */}
                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 10 }}>
                    <Button
                        key="push"
                        onClick={updateSpecialPriceModelNos}
                    >
                        修改
                    </Button>
                </div>
            </div>
        </Card>
    </>
}

export default PoisonPage;
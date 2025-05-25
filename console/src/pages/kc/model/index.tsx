import {
    Button,
    Card,
    Form,
    Input,
    message,
    Modal,
    Select,
    Space,
    Table
} from "antd";
import React, {useEffect, useState} from "react";
import {doGetRequest, doPostRequest} from "@/util/http";
import {SETTING_API} from "@/services/shoes";

const ModelPage = () => {
    const [mustCrawlModelNos, setMustCrawlModelNos] = useState('');
    const [forbiddenCrawlModelNos, setForbiddenCrawlModelNos] = useState('');

    useEffect(() => {
        queryMustCrawlModelNos();
        queryForbiddenCrawlModelNos();
    }, []);


    const queryMustCrawlModelNos = () => {
        doGetRequest(SETTING_API.QUERY_MUST_CRAWL_MODEL_NOS, {}, {
            onSuccess: res => {
                setMustCrawlModelNos(res.data);
            }
        });
    }

    const updateMustCrawlModelNos = () => {
        const modelNos = mustCrawlModelNos;
        doPostRequest(SETTING_API.UPDATE_MUST_CRAWL_MODEL_NOS, {modelNos}, {
            onSuccess: _ => {
                message.success("修改成功").then();
            }
        });
    }

    const queryForbiddenCrawlModelNos = () => {
        doGetRequest(SETTING_API.QUERY_FORBIDDEN_CRAWL_MODEL_NOS, {}, {
            onSuccess: res => {
                setForbiddenCrawlModelNos(res.data);
            }
        });
    }

    const updateForbiddenCrawlModelNos = () => {
        const modelNos = forbiddenCrawlModelNos;
        doPostRequest(SETTING_API.UPDATE_FORBIDDEN_CRAWL_MODEL_NOS, {modelNos}, {
            onSuccess: _ => {
                message.success("修改成功").then();
            }
        });
    }

    return <>
        <Card title={"必爬货号"} style={{ marginTop: 10 }}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch' }}>
                <Input.TextArea rows={15} value={mustCrawlModelNos} onChange={e => setMustCrawlModelNos(e.target.value)} />

                {/* 按钮容器，使用 flex-end 实现右对齐 */}
                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 10 }}>
                    <Button
                        key="push"
                        onClick={updateMustCrawlModelNos}
                    >
                        修改
                    </Button>
                </div>
            </div>
        </Card>

        <Card title={"禁爬货号"} style={{ marginTop: 10 }}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch' }}>
                <Input.TextArea rows={15} value={forbiddenCrawlModelNos} onChange={e => setForbiddenCrawlModelNos(e.target.value)}/>

                {/* 按钮容器，使用 flex-end 实现右对齐 */}
                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 10 }}>
                    <Button
                        key="push"
                        onClick={updateForbiddenCrawlModelNos}
                    >
                        修改
                    </Button>
                </div>
            </div>
        </Card>
    </>
}

export default ModelPage;
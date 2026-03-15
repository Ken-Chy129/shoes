import {
    Button,
    Card,
    Input,
    message,
    Upload,
    Space,
} from "antd";
import {UploadOutlined} from "@ant-design/icons";
import React, {useEffect, useRef} from "react";
import {doGetRequest, doPostRequest} from "@/util/http";
import {SETTING_API} from "@/services/shoes";

const ModelPage = () => {
    const mustCrawlRef = useRef<any>(null);
    const forbiddenCrawlRef = useRef<any>(null);
    const notCompareRef = useRef<any>(null);
    const forbiddenType = 4;
    const notCompareType = 2;

    useEffect(() => {
        queryMustCrawlModelNos();
        queryForbiddenCrawlModelNos();
        queryNotCompareModelNos();
    }, []);

    const queryMustCrawlModelNos = () => {
        doGetRequest(SETTING_API.QUERY_MUST_CRAWL_MODEL_NOS, {}, {
            onSuccess: res => {
                if (mustCrawlRef.current) {
                    mustCrawlRef.current.resizableTextArea.textArea.value = res.data || '';
                }
            }
        });
    }

    const updateMustCrawlModelNos = () => {
        const modelNos = mustCrawlRef.current?.resizableTextArea?.textArea?.value || '';
        doPostRequest(SETTING_API.UPDATE_MUST_CRAWL_MODEL_NOS, {modelNos}, {
            onSuccess: _ => {
                message.success("修改成功").then();
            }
        });
    }

    const handleFileUpload = (file: File) => {
        const reader = new FileReader();
        reader.onload = (e) => {
            const content = e.target?.result as string;
            if (content && mustCrawlRef.current) {
                const currentValue = mustCrawlRef.current.resizableTextArea.textArea.value || '';
                const newLines = content.split(/\r?\n/).filter(line => line.trim());
                const newValue = currentValue ? currentValue + '\n' + newLines.join('\n') : newLines.join('\n');
                mustCrawlRef.current.resizableTextArea.textArea.value = newValue;
                message.success(`导入 ${newLines.length} 条货号`);
            }
        };
        reader.readAsText(file);
        return false; // 阻止默认上传行为
    }

    const queryForbiddenCrawlModelNos = () => {
        doGetRequest(SETTING_API.QUERY_CUSTOM_MODEL_NOS, {type: forbiddenType}, {
            onSuccess: res => {
                if (forbiddenCrawlRef.current) {
                    forbiddenCrawlRef.current.resizableTextArea.textArea.value = res.data || '';
                }
            }
        });
    }

    const updateForbiddenCrawlModelNos = () => {
        const modelNos = forbiddenCrawlRef.current?.resizableTextArea?.textArea?.value || '';
        doPostRequest(SETTING_API.UPDATE_CUSTOM_MODEL_NOS, {modelNos, type: forbiddenType}, {
            onSuccess: _ => {
                message.success("修改成功").then();
            }
        });
    }

    const queryNotCompareModelNos = () => {
        doGetRequest(SETTING_API.QUERY_CUSTOM_MODEL_NOS, {type: notCompareType}, {
            onSuccess: res => {
                if (notCompareRef.current) {
                    notCompareRef.current.resizableTextArea.textArea.value = res.data || '';
                }
            }
        });
    }

    const updateNotCompareModelNos = () => {
        const modelNos = notCompareRef.current?.resizableTextArea?.textArea?.value || '';
        doPostRequest(SETTING_API.UPDATE_CUSTOM_MODEL_NOS, {modelNos, type: notCompareType}, {
            onSuccess: _ => {
                message.success("修改成功").then();
            }
        });
    }

    return <>
        <Card title={"必爬货号"}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch' }}>
                <Input.TextArea ref={mustCrawlRef} rows={15} />

                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 10 }}>
                    <Space>
                        <Upload
                            accept=".txt"
                            showUploadList={false}
                            beforeUpload={handleFileUpload}
                        >
                            <Button icon={<UploadOutlined />}>导入TXT</Button>
                        </Upload>
                        <Button
                            key="push"
                            onClick={updateMustCrawlModelNos}
                        >
                            修改
                        </Button>
                    </Space>
                </div>
            </div>
        </Card>

        <Card title={"禁爬货号"} style={{ marginTop: 10 }}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch' }}>
                <Input.TextArea ref={forbiddenCrawlRef} rows={15} />

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

        <Card title={"不比价货号"} style={{ marginTop: 10 }}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch' }}>
                <Input.TextArea ref={notCompareRef} rows={15} />

                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 10 }}>
                    <Button
                        key="push"
                        onClick={updateNotCompareModelNos}
                    >
                        修改
                    </Button>
                </div>
            </div>
        </Card>
    </>
}

export default ModelPage;
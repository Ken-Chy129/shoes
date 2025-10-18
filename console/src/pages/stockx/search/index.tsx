import {
    Button,
    Card,
    Form,
    Input,
    InputNumber,
    message,
    Modal,
    Progress,
    Select,
    Space,
    Table,
    Tag,
    Tooltip
} from "antd";
import React, {useEffect, useState, useRef} from "react";
import {DownloadOutlined, PlusOutlined} from "@ant-design/icons";
import {doGetRequest, doPostRequest} from "@/util/http";
import {SEARCH_TASK_API} from "@/services/stockx";
import {STOCKX_DOWNLOAD_API} from "@/services/file";

interface SearchTask {
    id: number;
    query: string;
    sorts: string;
    pageCount: number;
    progress: number;
    status: string;
    filePath: string;
    startTime: string;
    endTime: string;
    gmtCreate: string;
    gmtModified: string;
}

const SearchPage = () => {
    const [conditionForm] = Form.useForm();
    const [createTaskForm] = Form.useForm();

    const [taskList, setTaskList] = useState<SearchTask[]>([]);
    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [total, setTotal] = useState(0);

    const [showCreateModal, setShowCreateModal] = useState(false);
    const [loading, setLoading] = useState(false);

    const timerRef = useRef<NodeJS.Timeout | null>(null);

    useEffect(() => {
        queryTaskList();
    }, [pageIndex, pageSize]);

    useEffect(() => {
        const hasRunningTasks = taskList.some(task =>
            task.status === 'running' || task.status === 'pending'
        );

        if (hasRunningTasks) {
            if (!timerRef.current) {
                timerRef.current = setInterval(() => {
                    queryTaskList(true);
                }, 5000);
            }
        } else {
            if (timerRef.current) {
                clearInterval(timerRef.current);
                timerRef.current = null;
            }
        }

        return () => {
            if (timerRef.current) {
                clearInterval(timerRef.current);
                timerRef.current = null;
            }
        };
    }, [taskList]);

    const queryTaskList = (silent = false) => {
        const status = conditionForm.getFieldValue("status");
        doGetRequest(SEARCH_TASK_API.GET_TASKS, {status, pageIndex, pageSize}, {
            onSuccess: res => {
                setTaskList(res.data);
                setTotal(res.total);
                if (!silent) {
                    message.success("\u67e5\u8be2\u6210\u529f");
                }
            }
        });
    }

    const createSearchTask = () => {
        createTaskForm.validateFields().then(values => {
            setLoading(true);
            const {query, sorts, pageCount} = values;
            const sortsStr = sorts.join(',');

            doPostRequest(SEARCH_TASK_API.CREATE, {query, sorts: sortsStr, pageCount}, {
                onSuccess: res => {
                    message.success(`\u4efb\u52a1\u521b\u5efa\u6210\u529f\uff0c\u4efb\u52a1ID: ${res.data}`);
                    handleCreateModalClose();
                    queryTaskList();
                },
                onFinally: () => {
                    setLoading(false);
                }
            });
        });
    }

    const downloadResult = (taskId: number) => {
        window.open(`http://localhost:8080${STOCKX_DOWNLOAD_API.SEARCH}?searchTaskId=${taskId}`);
    }

    const handleCreateModalOpen = () => {
        setShowCreateModal(true);
    }

    const handleCreateModalClose = () => {
        setShowCreateModal(false);
        createTaskForm.resetFields();
    }

    const getStatusTag = (status: string) => {
        const statusConfig: Record<string, {color: string, text: string}> = {
            pending: {color: 'default', text: '\u5f85\u6267\u884c'},
            running: {color: 'processing', text: '\u6267\u884c\u4e2d'},
            success: {color: 'success', text: '\u6210\u529f'},
            failed: {color: 'error', text: '\u5931\u8d25'},
        };
        const config = statusConfig[status] || {color: 'default', text: status};
        return <Tag color={config.color}>{config.text}</Tag>;
    }

    const sortOptions = [
        {label: '\u7cbe\u9009', value: 'featured'},
        {label: 'Top Selling', value: 'most-active'},
        {label: 'Price: Low to High', value: 'lowest_ask'},
        {label: '\u51fa\u4ef7: \u4ece\u9ad8\u5230\u4f4e', value: 'highest_bid'},
        {label: 'Recent High Bids', value: 'recent_bids'},
        {label: 'Recent Price Drops', value: 'recent_asks'},
        {label: 'Total Sold: High to Low', value: 'deadstock_sold'},
        {label: '\u53d1\u5e03\u65e5\u671f', value: 'release_date'},
        {label: 'Price Premium: High to Low', value: 'price_premium'},
        {label: 'Last Sale: High to Low', value: 'last_sale'},
    ];

    const columns = [
        {
            title: 'ID',
            dataIndex: 'id',
            key: 'id',
            width: '5%',
        },
        {
            title: '\u641c\u7d22\u5173\u952e\u8bcd',
            dataIndex: 'query',
            key: 'query',
            width: '10%',
        },
        {
            title: '\u6392\u5e8f\u89c4\u5219',
            dataIndex: 'sorts',
            key: 'sorts',
            width: '12%',
            render: (sorts: string) => {
                const sortArray = sorts.split(',');
                return (
                    <div>
                        {sortArray.map((sort, index) => {
                            const option = sortOptions.find(opt => opt.value === sort.trim());
                            return (
                                <Tag key={index} style={{marginBottom: 4}}>
                                    {option?.label || sort}
                                </Tag>
                            );
                        })}
                    </div>
                );
            }
        },
        {
            title: '\u6bcf\u9875\u6570\u91cf',
            dataIndex: 'pageCount',
            key: 'pageCount',
            width: '6%',
        },
        {
            title: '\u8fdb\u5ea6',
            dataIndex: 'progress',
            key: 'progress',
            width: '10%',
            render: (progress: number, record: SearchTask) => (
                <Progress
                    percent={progress}
                    size="small"
                    status={record.status === 'failed' ? 'exception' :
                            record.status === 'success' ? 'success' :
                            'active'}
                />
            )
        },
        {
            title: '\u72b6\u6001',
            dataIndex: 'status',
            key: 'status',
            width: '7%',
            render: (status: string) => getStatusTag(status)
        },
        {
            title: '\u6587\u4ef6\u8def\u5f84',
            dataIndex: 'filePath',
            key: 'filePath',
            width: '15%',
            render: (filePath: string) => {
                if (!filePath) return '-';
                const fileName = filePath.split('/').pop() || filePath;
                return (
                    <Tooltip title={filePath}>
                        <span style={{cursor: 'pointer'}}>{fileName}</span>
                    </Tooltip>
                );
            }
        },
        {
            title: '\u5f00\u59cb\u65f6\u95f4',
            dataIndex: 'startTime',
            key: 'startTime',
            width: '10%',
        },
        {
            title: '\u7ed3\u675f\u65f6\u95f4',
            dataIndex: 'endTime',
            key: 'endTime',
            width: '10%',
        },
        {
            title: '\u521b\u5efa\u65f6\u95f4',
            dataIndex: 'gmtCreate',
            key: 'gmtCreate',
            width: '10%',
        },
        {
            title: '\u64cd\u4f5c',
            key: 'action',
            width: '8%',
            render: (text: string, record: SearchTask) => (
                <Space>
                    {record.status === 'success' && record.filePath && (
                        <Button
                            type="primary"
                            size="small"
                            icon={<DownloadOutlined />}
                            onClick={() => downloadResult(record.id)}
                        >
                            \u4e0b\u8f7d
                        </Button>
                    )}
                </Space>
            ),
        }
    ];

    return <>
        <Card
            title="\u641c\u7d22\u4efb\u52a1\u7ba1\u7406"
            extra={
                <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={handleCreateModalOpen}
                >
                    \u521b\u5efa\u641c\u7d22\u4efb\u52a1
                </Button>
            }
        >
            <Form form={conditionForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap", marginBottom: 16}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="status" label="\u4efb\u52a1\u72b6\u6001">
                        <Select
                            style={{width: 160}}
                            placeholder="\u8bf7\u9009\u62e9\u72b6\u6001"
                            allowClear
                            options={[
                                {label: '\u5f85\u6267\u884c', value: 'pending'},
                                {label: '\u6267\u884c\u4e2d', value: 'running'},
                                {label: '\u6267\u884c\u6210\u529f', value: 'success'},
                                {label: '\u6267\u884c\u5931\u8d25', value: 'failed'},
                            ]}
                        />
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" onClick={() => queryTaskList()}>
                            \u67e5\u8be2
                        </Button>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 10}}>
                        <Button onClick={() => {
                            conditionForm.resetFields();
                            queryTaskList();
                        }}>
                            \u91cd\u7f6e
                        </Button>
                    </Form.Item>
                </div>
            </Form>
            <Table
                columns={columns}
                dataSource={taskList}
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
            title="\u521b\u5efa\u641c\u7d22\u4efb\u52a1"
            open={showCreateModal}
            onCancel={handleCreateModalClose}
            footer={[
                <Button key="cancel" onClick={handleCreateModalClose}>
                    \u53d6\u6d88
                </Button>,
                <Button
                    key="submit"
                    type="primary"
                    loading={loading}
                    onClick={createSearchTask}
                >
                    \u521b\u5efa
                </Button>
            ]}
            width={600}
        >
            <Form
                form={createTaskForm}
                layout="vertical"
                style={{marginTop: 20}}
            >
                <Form.Item
                    name="query"
                    label="\u641c\u7d22\u5173\u952e\u8bcd"
                    rules={[{required: true, message: '\u8bf7\u8f93\u5165\u641c\u7d22\u5173\u952e\u8bcd'}]}
                >
                    <Input placeholder="\u8bf7\u8f93\u5165\u641c\u7d22\u5173\u952e\u8bcd\uff0c\u5982: Jordan" />
                </Form.Item>
                <Form.Item
                    name="sorts"
                    label="\u6392\u5e8f\u89c4\u5219\uff08\u53ef\u591a\u9009\uff09"
                    rules={[{required: true, message: '\u8bf7\u9009\u62e9\u81f3\u5c11\u4e00\u4e2a\u6392\u5e8f\u89c4\u5219'}]}
                >
                    <Select
                        mode="multiple"
                        placeholder="\u8bf7\u9009\u62e9\u6392\u5e8f\u89c4\u5219"
                        options={sortOptions}
                        maxTagCount="responsive"
                    />
                </Form.Item>
                <Form.Item
                    name="pageCount"
                    label="\u6bcf\u4e2a\u6392\u5e8f\u67e5\u8be2\u7684\u9875\u6570"
                    rules={[{required: true, message: '\u8bf7\u8f93\u5165\u9875\u6570'}]}
                    initialValue={10}
                >
                    <InputNumber
                        min={1}
                        max={100}
                        style={{width: '100%'}}
                        placeholder="\u8bf7\u8f93\u5165\u9875\u6570"
                    />
                </Form.Item>
            </Form>
        </Modal>
    </>
}

export default SearchPage;

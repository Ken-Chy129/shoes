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
import {DownloadOutlined, PlusOutlined, MinusCircleOutlined} from "@ant-design/icons";
import {doGetRequest, doPostRequest} from "@/util/http";
import {SEARCH_TASK_API} from "@/services/stockx";
import {STOCKX_DOWNLOAD_API} from "@/services/file";

interface SearchTask {
    id: number;
    query: string;
    sorts: string;
    pageCount: number;
    searchType: string;
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
                    message.success("查询成功");
                }
            }
        });
    }

    const createSearchTask = () => {
        createTaskForm.validateFields().then(values => {
            setLoading(true);
            const {queries, sorts, pageCount, searchType} = values;
            const sortsStr = sorts.join(',');

            // 统计总任务数和完成数
            let totalTasks = queries.length;
            let completedTasks = 0;
            let failedTasks = 0;

            // 为每个关键词创建任务
            queries.forEach((queryItem: { keyword: string }, index: number) => {
                doPostRequest(SEARCH_TASK_API.CREATE, {
                    query: queryItem.keyword,
                    sorts: sortsStr,
                    pageCount,
                    searchType
                }, {
                    onSuccess: res => {
                        completedTasks++;
                        if (completedTasks + failedTasks === totalTasks) {
                            if (failedTasks === 0) {
                                message.success(`全部任务创建成功！共创建 ${totalTasks} 个任务`);
                            } else {
                                message.warning(`任务创建完成：成功 ${completedTasks} 个，失败 ${failedTasks} 个`);
                            }
                            handleCreateModalClose();
                            queryTaskList();
                            setLoading(false);
                        }
                    },
                    onError: err => {
                        failedTasks++;
                        if (completedTasks + failedTasks === totalTasks) {
                            message.warning(`任务创建完成：成功 ${completedTasks} 个，失败 ${failedTasks} 个`);
                            handleCreateModalClose();
                            queryTaskList();
                            setLoading(false);
                        }
                    }
                });
            });
        }).catch(err => {
            console.error('表单验证失败:', err);
        });
    }

    const downloadResult = (taskId: number) => {
        window.open(`http://localhost:8080${STOCKX_DOWNLOAD_API.DOWNLOAD_SEARCH}?searchTaskId=${taskId}`);
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
            pending: {color: 'default', text: '待执行'},
            running: {color: 'processing', text: '执行中'},
            success: {color: 'success', text: '执行成功'},
            failed: {color: 'error', text: '执行失败'},
        };
        const config = statusConfig[status] || {color: 'default', text: status};
        return <Tag color={config.color}>{config.text}</Tag>;
    }

    const sortOptions = [
        {label: '精选', value: 'featured'},
        {label: 'Top Selling', value: 'most-active'},
        {label: 'Price: Low to High', value: 'lowest_ask'},
        {label: '出价: 从高到低', value: 'highest_bid'},
        {label: 'Recent High Bids', value: 'recent_bids'},
        {label: 'Recent Price Drops', value: 'recent_asks'},
        {label: 'Total Sold: High to Low', value: 'deadstock_sold'},
        {label: '发布日期', value: 'release_date'},
        {label: 'Price Premium: High to Low', value: 'price_premium'},
        {label: 'Last Sale: High to Low', value: 'last_sale'},
    ];

    const defaultSorts = sortOptions.map(option => option.value);

    const getSearchTypeText = (category: string) => {
        const searchTypeMap: Record<string, string> = {
            shoes: '鞋类',
            clothes: '服饰',
        };
        return searchTypeMap[category] || category;
    }

    const columns = [
        {
            title: 'ID',
            dataIndex: 'id',
            key: 'id',
            width: '5%',
        },
        {
            title: '搜索关键词',
            dataIndex: 'query',
            key: 'query',
            width: '10%',
        },
        {
            title: '搜索类型',
            dataIndex: 'searchType',
            key: 'searchType',
            width: '6%',
            render: (searchType: string) => getSearchTypeText(searchType)
        },
        {
            title: '排序规则',
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
            title: '爬取页面数',
            dataIndex: 'pageCount',
            key: 'pageCount',
            width: '6%',
        },
        {
            title: '进度',
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
            title: '状态',
            dataIndex: 'status',
            key: 'status',
            width: '7%',
            render: (status: string) => getStatusTag(status)
        },
        {
            title: '开始时间',
            dataIndex: 'startTime',
            key: 'startTime',
            width: '10%',
        },
        {
            title: '结束时间',
            dataIndex: 'endTime',
            key: 'endTime',
            width: '10%',
        },
        {
            title: '操作',
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
                            下载
                        </Button>
                    )}
                </Space>
            ),
        }
    ];

    return <>
        <Card
            title="任务列表"
            extra={
                <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={handleCreateModalOpen}
                >
                    新建任务
                </Button>
            }
        >
            <Form form={conditionForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap", marginBottom: 16}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="status" label="任务状态">
                        <Select
                            style={{width: 160}}
                            placeholder="请选择字段"
                            allowClear
                            options={[
                                {label: '待执行', value: 'pending'},
                                {label: '运行中', value: 'running'},
                                {label: '运行成功', value: 'success'},
                                {label: '运行失败', value: 'failed'},
                            ]}
                        />
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" onClick={() => queryTaskList()}>
                            查询
                        </Button>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 10}}>
                        <Button onClick={() => {
                            conditionForm.resetFields();
                            queryTaskList();
                        }}>
                            重置
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
            title="新建任务"
            open={showCreateModal}
            onCancel={handleCreateModalClose}
            footer={[
                <Button key="cancel" onClick={handleCreateModalClose}>
                    取消
                </Button>,
                <Button
                    key="submit"
                    type="primary"
                    loading={loading}
                    onClick={createSearchTask}
                >
                    提交
                </Button>
            ]}
            width={600}
        >
            <Form
                form={createTaskForm}
                layout="vertical"
                style={{marginTop: 20}}
                initialValues={{
                    queries: [{keyword: ''}],
                    searchType: 'shoes',
                    sorts: defaultSorts,
                    pageCount: 10
                }}
            >
                <Form.List
                    name="queries"
                    rules={[
                        {
                            validator: async (_, queries) => {
                                if (!queries || queries.length < 1) {
                                    return Promise.reject(new Error('至少需要添加一个关键词'));
                                }
                            },
                        },
                    ]}
                >
                    {(fields, {add, remove}, {errors}) => (
                        <>
                            <div style={{marginBottom: 8, fontWeight: 500}}>关键词列表</div>
                            {fields.map((field, index) => (
                                <Form.Item
                                    required={false}
                                    key={field.key}
                                >
                                    <Form.Item
                                        {...field}
                                        name={[field.name, 'keyword']}
                                        validateTrigger={['onChange', 'onBlur']}
                                        rules={[
                                            {
                                                required: true,
                                                whitespace: true,
                                                message: "请输入关键词或删除此项",
                                            },
                                        ]}
                                        noStyle
                                    >
                                        <Input
                                            placeholder={`关键词 ${index + 1}`}
                                            style={{
                                                width: fields.length > 1 ? 'calc(100% - 40px)' : '100%',
                                                marginRight: fields.length > 1 ? 8 : 0
                                            }}
                                        />
                                    </Form.Item>
                                    {fields.length > 1 && (
                                        <MinusCircleOutlined
                                            className="dynamic-delete-button"
                                            onClick={() => remove(field.name)}
                                            style={{fontSize: 20, color: '#ff4d4f', cursor: 'pointer'}}
                                        />
                                    )}
                                </Form.Item>
                            ))}
                            <Form.Item>
                                <Button
                                    type="dashed"
                                    onClick={() => add()}
                                    style={{width: '100%'}}
                                    icon={<PlusOutlined />}
                                >
                                    添加关键词
                                </Button>
                                <Form.ErrorList errors={errors} />
                            </Form.Item>
                        </>
                    )}
                </Form.List>
                <Form.Item
                    name="searchType"
                    label="搜索类型"
                    rules={[{required: true}]}
                >
                    <Select
                        placeholder="请选择搜索类型"
                        options={[
                            {label: '鞋类', value: 'shoes'},
                            {label: '服饰', value: 'clothes'},
                        ]}
                    />
                </Form.Item>
                <Form.Item
                    name="sorts"
                    label="排序方式"
                    rules={[{required: true}]}
                >
                    <Select
                        mode="multiple"
                        placeholder="请选择一个或多个排序方式"
                        options={sortOptions}
                        maxTagCount="responsive"
                    />
                </Form.Item>
                <Form.Item
                    name="pageCount"
                    label="爬取页面数量(每页40个商品)"
                    rules={[{required: true}]}
                >
                    <InputNumber
                        min={1}
                        max={100}
                        style={{width: '100%'}}
                    />
                </Form.Item>
            </Form>
        </Modal>
    </>
}

export default SearchPage;

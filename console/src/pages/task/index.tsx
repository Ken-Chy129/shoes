import {
    Button, DatePicker,
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
import {TASK_API} from "@/services/task";

const TaskPage = () => {
    const [conditionForm] = Form.useForm();

    const [taskList, setTaskList] = useState<Template[]>([]);

    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [total, setTotal] = useState(0);

    useEffect(() => {
        queryTaskList();
    }, []);

    const queryTaskList = () => {
        console.log(conditionForm);
        const taskType = conditionForm.getFieldValue("taskType");
        const platform = conditionForm.getFieldValue("platform");
        const startTime = conditionForm.getFieldValue("startTime");
        const endTime = conditionForm.getFieldValue("endTime");
        const status = conditionForm.getFieldValue("status");
        doGetRequest(TASK_API.PAGE, {taskType, platform, startTime, endTime, status, pageIndex, pageSize}, {
            onSuccess: res => setTaskList(res.data)
        });
    }

    const columns = [
        {
            title: '任务类型',
            dataIndex: 'type',
            key: 'type',
            width: '22%'
        },
        {
            title: '平台',
            dataIndex: 'platform',
            key: 'platform',
            width: '18%', // 设置列宽为30%
        },
        {
            title: '开始时间',
            dataIndex: 'startTime',
            key: 'startTime',
            width: '23%', // 设置列宽为30%
        },
        {
            title: '结束时间',
            dataIndex: 'endTime',
            key: 'endTime',
            width: '15%', // 设置列宽为30%
        },
        {
            title: '状态',
            dataIndex: 'status',
            key: 'status',
            width: '15%', // 设置列宽为30%
        },
        {
            title: '扩展属性',
            dataIndex: 'attributes',
            key: 'attributes',
            width: '15%', // 设置列宽为30%
        },
        // {
        //     title: '操作',
        //     key: 'action',
        //     render: (text: string, templateField: { id: string, fieldId: string, fieldValue: string }) => (
        //         <span>
        //           <Button type="primary" onClick={() => handleFieldPushModal(templateField.id)}>
        //             值推送
        //           </Button>
        //           <Button type="primary" style={{marginLeft: 20}} onClick={() => handleOpenFieldModifiedModal(templateField.id)}>
        //             值修改
        //           </Button>
        //           <Button type="primary" key="delete" style={{marginLeft: 20}} onClick={() => handleOpenFieldDeleteModal(templateField.id)}>
        //             字段删除
        //           </Button>
        //         </span>
        //     ),
        //     width: '22%', // 设置列宽为30%
        // }
    ];

    return <>
        <Form form={conditionForm}
              style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
            <div style={{display: "flex"}}>
                <Form.Item name="taskType" label="任务类型">
                    <Select
                        style={{width: 160}}
                        placeholder="请选择字段"
                        allowClear
                        showSearch={true}
                        optionFilterProp="label"
                        options={
                            [
                                {label: '全量刷新商品', value: 1},
                                {label: '增量刷新商品', value: 2},
                                {label: '刷新商品价格', value: 3},
                                {label: '改价', value: 4},
                            ]
                        }
                        notFoundContent={"该命名空间下暂无字段"}
                    />
                </Form.Item>
                <Form.Item name="platform" label="平台" style={{marginLeft: 20}}>
                    <Select
                        style={{width: 160}}
                        placeholder="请选择字段"
                        allowClear
                        showSearch={true}
                        optionFilterProp="label"
                        options={
                            [
                                {label: '得物', value: 'poison'},
                                {label: 'KickScrew', value: 'kc'},
                                // {label: '快速价格', value: 'fast'},
                            ]
                        }
                        notFoundContent={"该命名空间下暂无字段"}
                    />
                </Form.Item>
                <Form.Item name="startTime" label="开始时间" style={{marginLeft: 20}}>
                    <DatePicker
                        showTime={{ format: 'HH:mm' }}
                        format="YYYY-MM-DD HH:mm"
                        onChange={(value, dateString) => {
                            console.log('Selected Time: ', value);
                            console.log('Formatted Selected Time: ', dateString);
                        }}
                    />
                </Form.Item>
                <Form.Item name="endTime" label="结束时间" style={{marginLeft: 20}}>
                    <DatePicker
                        showTime={{ format: 'HH:mm' }}
                        format="YYYY-MM-DD HH:mm"
                        onChange={(value, dateString) => {
                            console.log('Selected Time: ', value);
                            console.log('Formatted Selected Time: ', dateString);
                        }}
                    />
                </Form.Item>
                <Form.Item name="status" label="状态" style={{marginLeft: 20}}>
                    <Select
                        style={{width: 160}}
                        placeholder="请选择字段"
                        allowClear
                        showSearch={true}
                        optionFilterProp="label"
                        options={
                            [
                                {label: '运行中', value: 1},
                                {label: '成功', value: 2},
                                {label: '失败', value: 3},
                                {label: '暂停', value: 4},
                            ]
                        }
                        notFoundContent={"该命名空间下暂无字段"}
                    />
                </Form.Item>
                <Form.Item style={{marginLeft: 30}}>
                    <Button type="primary" htmlType="submit" onClick={queryTaskList}>
                        查询
                    </Button>
                </Form.Item>
                <Form.Item style={{marginLeft: 30}}>
                    <Button type="primary" htmlType="reset" onClick={() => conditionForm.resetFields()}>
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
    </>
}

export default TaskPage;
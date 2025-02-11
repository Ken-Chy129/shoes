import {
    Button,
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

const SettingPage = () => {
    const [conditionForm] = Form.useForm();
    const [newTemplateForm] = Form.useForm();
    const [deleteTemplateForm] = Form.useForm();
    const [addTemplateFieldForm] = Form.useForm();
    const [pushTemplateForm] = Form.useForm();

    const [fieldPushForm] = Form.useForm();
    const [modifiedModalForm] = Form.useForm();
    const [deleteTemplateFieldForm] = Form.useForm();

    const [templateList, setTemplateList] = useState<Template[]>([]);
    const [selectedTemplateId, setSelectedTemplateId] = useState<string>();
    const [templateFieldList, setTemplateFieldList] = useState<[]>([]);

    const [showNewTemplateModal, setShowNewTemplateModal] = useState<boolean>(false);
    const [showDeleteTemplateModal, setShowDeleteTemplateModal] = useState<boolean>(false);
    const [showAddTemplateFieldModal, setShowAddTemplateFieldModal] = useState<boolean>(false);
    const [showTemplatePushModal, setShowTemplatePushModal] = useState<boolean>(false);
    const [showFieldPushModal, setShowFieldPushModal] = useState<boolean>(false);
    const [showFieldModifiedModal, setShowFieldModifiedModal] = useState<boolean>(false);
    const [showFieldDeleteModal, setShowFieldDeleteModal] = useState<boolean>(false);

    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [total, setTotal] = useState(0);

    useEffect(() => {
        queryTemplateList();
    }, []);

    useEffect(() => {
        if (selectedTemplateId) {
            conditionForm.resetFields();
            queryTemplateFieldList();
        }
    }, [selectedTemplateId, pageIndex, pageSize]);

    const queryTemplateList = (selectedKey?: string) => {
        doGetRequest(TEMPLATE_API.LIST_BY_APPID, {}, {
            onSuccess: res => {
                res.data.forEach((template: any) => {
                    template.label = template.name;
                    template.key = template.id;
                    template.value = template.id;
                });
                setTemplateList(res.data);
                setSelectedTemplateId(selectedKey ?? String(res.data[0].id));
            }
        });
    }

    const queryTemplateFieldList = () => {
        const namespaceId = conditionForm.getFieldValue("namespaceId");
        const fieldId = conditionForm.getFieldValue("field");
        doGetRequest(TEMPLATE_API.PAGE_FIELD_BY_CONDITION, {
            templateId: selectedTemplateId,
            namespaceId,
            fieldId,
            pageIndex,
            pageSize
        }, {
            onSuccess: res => {
                setTotal(res.total);
                setTemplateFieldList(res.data);
            }
        });
    }

    const handleModalClose = () => {
        newTemplateForm.resetFields();
        deleteTemplateForm.resetFields();
        addTemplateFieldForm.resetFields();
        pushTemplateForm.resetFields();
        modifiedModalForm.resetFields();
        fieldPushForm.resetFields();
        setShowNewTemplateModal(false);
        setShowDeleteTemplateModal(false);
        setShowTemplatePushModal(false);
        setShowAddTemplateFieldModal(false);
        setShowFieldModifiedModal(false);
        setShowFieldPushModal(false);
        setShowFieldDeleteModal(false);
    }

    const handleFieldPushModal = (templateFieldId: string) => {
        fieldPushForm.setFieldValue("templateFieldId", templateFieldId);
        setShowFieldPushModal(true);
    }

    const handleOpenFieldModifiedModal = (templateFieldId: string) => {
        modifiedModalForm.setFieldValue("templateFieldId", templateFieldId);
        setShowFieldModifiedModal(true);
    }

    const handleOpenFieldDeleteModal = (templateFieldId: string) => {
        deleteTemplateFieldForm.setFieldValue("templateFieldId", templateFieldId);
        setShowFieldDeleteModal(true);
    }

    const handleUpdateTemplateField = () => {
        const templateFieldId = modifiedModalForm.getFieldValue("templateFieldId");
        const fieldValue = modifiedModalForm.getFieldValue("value");
        doPostRequest(TEMPLATE_API.UPDATE_FIELD, {templateFieldId, fieldValue}, {
            onSuccess: _ => {
                queryTemplateFieldList();
                handleModalClose();
                message.success("修改成功").then(_ => {});
            }
        });
    }

    const handleDeleteTemplateField = () => {
        const templateFieldId = deleteTemplateFieldForm.getFieldValue("templateFieldId");
        doDeleteRequest(TEMPLATE_API.DELETE_FIELD, {templateFieldId}, {
            onSuccess: _ => {
                queryTemplateList(String(selectedTemplateId));
                queryTemplateFieldList();
                handleModalClose();
                message.success("删除成功").then(_ => {});
            }
        });
    }

    const handleNewTemplate = () => {
        const name = newTemplateForm.getFieldValue("name");
        const description = newTemplateForm.getFieldValue("description");
        const fromTemplateId = newTemplateForm.getFieldValue("fromTemplate");
        doPostRequest(TEMPLATE_API.NEW, {name, description, fromTemplateId}, {
            onSuccess: res => {
                queryTemplateList(String(res.data));
                handleModalClose();
                message.success("推送成功").then(_ => {});
            }
        });
    }

    const handleDeleteTemplate = () => {
        const templateId = deleteTemplateForm.getFieldValue("template");
        doDeleteRequest(TEMPLATE_API.DELETE, {templateId}, {
            onSuccess: _ => {
                queryTemplateList();
                handleModalClose();
                message.success("删除成功").then(_ => {});
            }
        });
    }

    const handleAddTemplateField = () => {
        const templateId = addTemplateFieldForm.getFieldValue("template");
        const namespaceId = addTemplateFieldForm.getFieldValue("namespaceId");
        const fieldId = addTemplateFieldForm.getFieldValue("field");
        const fieldValue = addTemplateFieldForm.getFieldValue("fieldValue");
        doPostRequest(TEMPLATE_API.ADD_FIELD, {templateId, namespaceId, fieldId, fieldValue}, {
            onSuccess: _ => {
                queryTemplateList(String(templateId));
                queryTemplateFieldList();
                handleModalClose();
                message.success("添加成功").then(_ => {});
            }
        });
    }

    const handleTemplatePush = () => {
        const templateId = pushTemplateForm.getFieldValue("template");
        const machineType = pushTemplateForm.getFieldValue("machineType");
        const machines = pushTemplateForm.getFieldValue("machines")?.join(',');
        doPostRequest(TEMPLATE_API.PUSH, {templateId, machineType, machines}, {
            onSuccess: _ => {
                queryTemplateList(String(templateId));
                handleModalClose();
                message.success("推送成功").then(_ => {});
            }
        });
    }

    const handleFieldPush = () => {
        const templateFieldId = fieldPushForm.getFieldValue("templateFieldId");
        const machineType = fieldPushForm.getFieldValue("machineType");
        const machines = fieldPushForm.getFieldValue("machines")?.join(',');
        doPostRequest(TEMPLATE_API.PUSH_FIELD, {selectedTemplateId, templateFieldId, machineType, machines}, {
            onSuccess: _ => {
                handleModalClose();
                message.success("推送成功").then(_ => {});
            }
        });
    }

    const columns = [
        {
            title: '命名空间',
            dataIndex: 'namespace',
            key: 'namespace',
            width: '22%'
        },
        {
            title: '字段名',
            dataIndex: 'fieldName',
            key: 'fieldName',
            width: '18%', // 设置列宽为30%
        },
        {
            title: '字段值',
            dataIndex: 'fieldValue',
            key: 'fieldValue',
            width: '23%', // 设置列宽为30%
        },
        {
            title: '上次修改时间',
            dataIndex: 'gmtModified',
            key: 'gmtModified',
            width: '15%', // 设置列宽为30%
        },
        {
            title: '操作',
            key: 'action',
            render: (text: string, templateField: { id: string, fieldId: string, fieldValue: string }) => (
                <span>
                  <Button type="primary" onClick={() => handleFieldPushModal(templateField.id)}>
                    值推送
                  </Button>
                  <Button type="primary" style={{marginLeft: 20}} onClick={() => handleOpenFieldModifiedModal(templateField.id)}>
                    值修改
                  </Button>
                  <Button type="primary" key="delete" style={{marginLeft: 20}} onClick={() => handleOpenFieldDeleteModal(templateField.id)}>
                    字段删除
                  </Button>
                </span>
            ),
            width: '22%', // 设置列宽为30%
        }
    ];

    return <>
        <Tabs onChange={(key) => {console.log(key); setSelectedTemplateId(key as string)}} activeKey={selectedTemplateId}>
            {templateList.map(template => (
                <Tabs.TabPane tab={<Tooltip title={template.description}>{template.label}</Tooltip>}
                              key={template.key}/>
            ))}
        </Tabs>
        <Form form={conditionForm}
              style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
            <div style={{display: "flex"}}>
                <Form.Item name="namespaceId" label="命名空间">
                    <NamespaceSelect form={conditionForm}/>
                </Form.Item>
                <Form.Item name="field" label="字段" style={{marginLeft: 20}}>
                    <FieldSelect form={conditionForm}/>
                </Form.Item>
                <Form.Item style={{marginLeft: 30}}>
                    <Button type="primary" htmlType="submit" onClick={queryTemplateFieldList}>
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
                <Form.Item>
                    <Button htmlType="reset" onClick={() => setShowNewTemplateModal(true)}>
                        新建模板
                    </Button>
                </Form.Item>
                <Form.Item style={{marginLeft: 20}}>
                    <Button htmlType="reset" onClick={() => setShowDeleteTemplateModal(true)}>
                        删除模板
                    </Button>
                </Form.Item>
                <Form.Item style={{marginLeft: 20}}>
                    <Button htmlType="reset" onClick={() => setShowAddTemplateFieldModal(true)}>
                        新增字段
                    </Button>
                </Form.Item>
                <Form.Item style={{marginLeft: 20, marginRight: 10}}>
                    <Button htmlType="reset" onClick={() => setShowTemplatePushModal(true)}>
                        模板推送
                    </Button>
                </Form.Item>
            </div>
        </Form>
        <Table
            columns={columns}
            dataSource={templateFieldList}
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

        <Modal title="新建模板" open={showNewTemplateModal} onOk={handleModalClose} onCancel={handleModalClose}
               footer={[
                   <Space>
                       <Button key="save" onClick={handleNewTemplate}>
                           保存
                       </Button>
                       <Button key="close" onClick={handleModalClose}>
                           关闭
                       </Button>
                   </Space>
               ]}
               style={{maxWidth: 600}}
        >
            <Form form={newTemplateForm} style={{marginTop: 30}} labelCol={{span: 5}} wrapperCol={{span: 18}}>
                <Form.Item name={"name"} label={"模板名称"} required={true}>
                    <Input/>
                </Form.Item>
                <Form.Item name={"description"} label={"模板描述"}>
                    <Input.TextArea/>
                </Form.Item>
                <Form.Item name={"fromTemplate"} label={"来源模板"}>
                    <Select
                        placeholder="请选择模板"
                        allowClear
                        showSearch={true}
                        optionFilterProp={"label"}
                        options={templateList}
                        notFoundContent={"暂无可复制模板"}
                    />
                </Form.Item>
            </Form>
        </Modal>
        <Modal title="删除模板" open={showDeleteTemplateModal} onOk={handleModalClose} onCancel={handleModalClose}
               footer={[
                   <Space>
                       <Button key="save" onClick={handleDeleteTemplate}>
                           删除
                       </Button>
                       <Button key="close" onClick={handleModalClose}>
                           关闭
                       </Button>
                   </Space>
               ]}
               style={{maxWidth: 600}}
        >
            <Form form={deleteTemplateForm} style={{marginTop: 30}} labelCol={{span: 5}} wrapperCol={{span: 18}}>
                <Form.Item name={"template"} label={"选择模板"}>
                    <Select
                        placeholder="请选择要删除的模板"
                        allowClear
                        showSearch={true}
                        optionFilterProp={"label"}
                        options={templateList}
                        notFoundContent={"暂无可复制模板"}
                    />
                </Form.Item>
            </Form>
        </Modal>
        <Modal title="新增字段" open={showAddTemplateFieldModal} onOk={handleModalClose} onCancel={handleModalClose}
               footer={[
                   <Space>
                       <Button key="save" onClick={handleAddTemplateField}>
                           保存
                       </Button>
                       <Button key="close" onClick={handleModalClose}>
                           关闭
                       </Button>
                   </Space>
               ]}
               style={{maxWidth: 600}}
        >
            <Form form={addTemplateFieldForm} style={{marginTop: 30}} labelCol={{span: 5}} wrapperCol={{span: 18}}>
                <Form.Item name={"template"} label={"选择模板"}>
                    <Select
                        placeholder="请选择模板"
                        allowClear
                        options={templateList}
                        notFoundContent={"暂无命名空间"}
                    />
                </Form.Item>
                <Form.Item name={"name"} label={"选择字段"} required={true}>
                    <Form.Item name={"namespaceId"} style={{marginBottom: 0}}>
                        <NamespaceSelect form={addTemplateFieldForm}/>
                    </Form.Item>
                    <Form.Item name={"field"} style={{marginTop: 10, marginBottom: 0}}>
                        <FieldSelect form={addTemplateFieldForm}/>
                    </Form.Item>
                </Form.Item>
                <Form.Item name={"fieldValue"} label={"字段值"}>
                    <Input.TextArea/>
                </Form.Item>
            </Form>
        </Modal>
        <Modal title="模板推送" open={showTemplatePushModal} onOk={handleModalClose} onCancel={handleModalClose}
               footer={[
                   <Space>
                       <Button key="save" onClick={handleTemplatePush}>
                           保存
                       </Button>
                       <Button key="close" onClick={handleModalClose}>
                           关闭
                       </Button>
                   </Space>
               ]}
               style={{maxWidth: 600}}
        >
            <Form form={pushTemplateForm} style={{marginTop: 30}} labelCol={{span: 5}} wrapperCol={{span: 18}}>
                <Form.Item name={"template"} label={"选择模板"}>
                    <Select
                        placeholder="请选择模板"
                        allowClear
                        showSearch={true}
                        optionFilterProp={"label"}
                        options={templateList}
                        notFoundContent={"暂无命名空间"}
                    />
                </Form.Item>
                <Form.Item name="machineType" label="推送方式">
                    <Radio.Group value={pushTemplateForm.getFieldValue("machineType")}>
                        <Radio value={"all"}>所有机器</Radio>
                        <Radio value={"specific"}>指定机器</Radio>
                    </Radio.Group>
                </Form.Item>
                <Form.Item
                    noStyle
                    shouldUpdate={(prevValues, currentValues) => prevValues.machineType !== currentValues.machineType}
                >
                    {({getFieldValue}) => {
                        return getFieldValue('machineType') === 'specific' ? (
                            <Form.Item name="machines" label="推送机器">
                                <MachineSelect mode="multiple" form={pushTemplateForm}/>
                            </Form.Item>
                        ) : null
                    }}
                </Form.Item>
            </Form>
        </Modal>


        <Modal
            title="修改"
            open={showFieldModifiedModal}
            onOk={handleModalClose}
            onCancel={handleModalClose}
            footer={[
                <Space>
                    <Button key="push" onClick={handleUpdateTemplateField}>
                        推送
                    </Button>
                    <Button key="close" onClick={handleModalClose}>
                        关闭
                    </Button>
                </Space>
            ]}
            style={{maxWidth: 600}}
        >
            <Form form={modifiedModalForm} style={{margin: 30}}>
                <Form.Item name={"value"} label={"新值"}>
                    <Input.TextArea/>
                </Form.Item>
            </Form>
        </Modal>
        <Modal
            title="模板值推送"
            open={showFieldPushModal}
            onOk={handleModalClose}
            onCancel={handleModalClose}
            footer={[
                <Space>
                    <Button key="back" onClick={handleFieldPush}>
                        推送
                    </Button>
                    <Button key="back" onClick={handleModalClose}>
                        关闭
                    </Button>
                </Space>
            ]}
            style={{maxWidth: 600}}
        >
            <Form form={fieldPushForm} style={{marginTop: 30}} labelCol={{span: 5}} wrapperCol={{span: 18}}>
                <Form.Item name="machineType" label="推送方式">
                    <Radio.Group value={fieldPushForm.getFieldValue("machineType")}>
                        <Radio value={"all"}>所有机器</Radio>
                        <Radio value={"specific"}>指定机器</Radio>
                    </Radio.Group>
                </Form.Item>
                <Form.Item
                    noStyle
                    shouldUpdate={(prevValues, currentValues) => prevValues.machineType !== currentValues.machineType}
                >
                    {({getFieldValue}) => {
                        return getFieldValue('machineType') === 'specific' ? (
                            <Form.Item name="machines" label="推送机器">
                                <MachineSelect mode="multiple" form={fieldPushForm}/>
                            </Form.Item>
                        ) : null
                    }}
                </Form.Item>
            </Form>
        </Modal>
        <Modal title={"字段删除"} open={showFieldDeleteModal} onOk={handleDeleteTemplateField}
               onCancel={handleModalClose} style={{maxWidth: 340}}>
            <p style={{marginTop: 20}}>是否确认进行删除</p>
        </Modal>
    </>
}

export default SettingPage;
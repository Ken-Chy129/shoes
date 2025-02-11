import {useState} from "react";

const Model = () => {
    const [appId, setAppId] = useState<number>()

    return {
        appId,
        setAppId
    }
}

export default Model;
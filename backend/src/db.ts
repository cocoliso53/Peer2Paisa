import axios from 'axios'
import dotenv from 'dotenv'
import path from 'path'
dotenv.config({
  path: path.resolve(__dirname, '../.env'),
})

const DB_USERNAME = process.env.DB_USERNAME 
const DB_PASSWORD = process.env.DB_PASSWORD

const couch = axios.create({
    baseURL: 'http://127.0.0.1:5984',
    auth: {
        username: DB_USERNAME!,
        password: DB_PASSWORD!,
    }
})

const handleCouchError = (err: unknown, context: string): never => {
    if (axios.isAxiosError(err)) {
        console.error('CouchDB request failed:', {
            context,
            status: err.response?.status,
            data: err.response?.data,
            message: err.message,
            url: err.config?.url,
            method: err.config?.method,
        });
        throw new Error(`CouchDB ${context} request failed: ${err.message}`);
    }

    console.error(`Unexpected error during CouchDB ${context} request:`, err);
    if (err instanceof Error) {
        throw err;
    }

    throw new Error(`Unexpected error during CouchDB ${context} request`);
}

// Assumes orders "table" exists
// check nulls, don't like that but works for now
export const getOrderById = async (orderId: string) => {
    try {
        const { data } = await couch.get(`/orders/${orderId}`)
        if (data?.error) {
            throw new Error(`Order ${orderId} not found: ${data.error}`)
        }
        return data
    } catch (err) {
        handleCouchError(err, `getOrderById(${orderId})`)
    }
}

// need to refine the types here
// Using this for both update and creating a new order
export const updateOrder = async (basicOrder: any) => {
    try {
        const { orderId } = basicOrder
        const { data } = await couch.put(
            `/orders/${orderId}`,
            basicOrder
        )
        return data
    } catch (err) {
        handleCouchError(err, `updateOrder(${basicOrder?.orderId ?? 'unknown'})`)
    }
}

export const getActiveOrderByParticipant = async (username: string) => {
    try {
        const { data } = await couch.post("/orders/_find", {
            selector: { 
                status: 'active',
                participants: { $all: [username]}
            },
        })

        return data
    } catch (err) {
        handleCouchError(err, `getActiveOrderByParticipant(${username})`)
    }
}

export const getOrdersByParticipantAndStatus = async (username: string, status: string) => {
    try {
        const { data } = await couch.post('/orders/_find', {
            selector: {
                status,
                participants: { $all: [username] }
            },
        })

        return data
    } catch (err) {
        handleCouchError(err, `getOrdersByParticipantAndStatus(${username}, ${status})`)
    }
}

// DELETE curl command
// curl -u user:pass -X DELETE   "http://127.0.0.1:5984/orders/GRBq2WvyoGXBwXJOwaqfrg?rev=1-eaf7143c0f911dfbf544a161e4bd1eac"

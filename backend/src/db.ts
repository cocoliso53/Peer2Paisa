import axios from 'axios'
import dotenv from 'dotenv'
import path from 'path'
import type { CouchFindResponse, CouchWriteResponse, OrderDocument, OrderState } from './types/fsm'
dotenv.config({
  path: path.resolve(__dirname, '../.env'),
})

const getRequiredEnv = (name: string): string => {
    const value = process.env[name]

    if (!value) {
        throw new Error(`Missing required environment variable: ${name}`)
    }

    return value
}

const COUCHDB_URL = getRequiredEnv('COUCHDB_URL')
const DB_USERNAME = getRequiredEnv('DB_USERNAME')
const DB_PASSWORD = getRequiredEnv('DB_PASSWORD')

const couch = axios.create({
    baseURL: COUCHDB_URL,
    auth: {
        username: DB_USERNAME,
        password: DB_PASSWORD,
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
export const getOrderById = async (orderId: string): Promise<OrderDocument> => {
    try {
        const { data } = await couch.get<OrderDocument>(`/orders/${orderId}`)
        if (data?.error) {
            throw new Error(`Order ${orderId} not found: ${data.error}`)
        }
        return data
    } catch (err) {
        return handleCouchError(err, `getOrderById(${orderId})`)
    }
}

export const updateOrder = async (basicOrder: OrderDocument): Promise<CouchWriteResponse> => {
    try {
        const { orderId } = basicOrder
        const { data } = await couch.put<CouchWriteResponse>(
            `/orders/${orderId}`,
            basicOrder
        )
        return data
    } catch (err) {
        return handleCouchError(err, `updateOrder(${basicOrder?.orderId ?? 'unknown'})`)
    }
}

export const getActiveOrderByParticipant = async (
    username: string,
): Promise<CouchFindResponse<OrderDocument>> => {
    try {
        const { data } = await couch.post<CouchFindResponse<OrderDocument>>("/orders/_find", {
            selector: { 
                status: 'active',
                participants: { $all: [username]}
            },
        })

        return data
    } catch (err) {
        return handleCouchError(err, `getActiveOrderByParticipant(${username})`)
    }
}

export const getActiveOrderByParticipantAndState = async (
    username: string,
    state: OrderState,
): Promise<CouchFindResponse<OrderDocument>> => {
    try {
        const { data } = await couch.post<CouchFindResponse<OrderDocument>>('/orders/_find', {
            selector: {
                status: 'active',
                state,
                participants: { $all: [username] }
            },
        })

        return data
    } catch (err) {
        return handleCouchError(err, `getActiveOrderByParticipantAndState(${username}, ${state})`)
    }
}

export const getOrdersByParticipantAndStatus = async (
    username: string,
    status: OrderDocument['status'],
): Promise<CouchFindResponse<OrderDocument>> => {
    try {
        const { data } = await couch.post<CouchFindResponse<OrderDocument>>('/orders/_find', {
            selector: {
                status,
                participants: { $all: [username] }
            },
        })

        return data
    } catch (err) {
        return handleCouchError(err, `getOrdersByParticipantAndStatus(${username}, ${status})`)
    }
}

// DELETE curl command
// curl -u user:pass -X DELETE   "$COUCHDB_URL/orders/<orderId>?rev=<rev>"

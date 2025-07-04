import dotenv from 'dotenv'
import path from 'path'

dotenv.config({
  path: path.resolve(__dirname, '../../.env'),
})


import { privateKeyToAccount, privateKeyToAddress } from 'viem/accounts'

const privateKey = process.env.PRIVATE_KEY
console.log("private", privateKey)

const address = privateKeyToAddress(privateKey as `0x${string}`)
console.log("address", address)
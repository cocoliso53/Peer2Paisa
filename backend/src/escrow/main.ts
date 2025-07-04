import { generatePrivateKey, privateKeyToAccount, privateKeyToAddress } from 'viem/accounts'

const pk = generatePrivateKey()
console.log("pk", pk)
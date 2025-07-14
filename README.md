# Peer-2-Paisa

A telegram bot powering Peer 2 Peer crypto <-> fiat ramps using smart contracts.  

## Installation 

To deploy your own version of the bot you'll need 

- An EOA (referred to as `bot` in the smartcontracts) that will be in charge of creating and controlling the escrow contracts. You'll need the provate key of the EOA
- An alchemy or equivalent rpc or url that will be used to connect and interact with the blockchain. 
- A telegram bot token
- A telegram channel that will work as the public "orderbook". Make sure add the telegram bot an admin on this channel. 


### Smart Contracts

Get both `EscrowFactory.sol` and `SwapEscrow.sol` in the `Peer2Paisa/smartcontracts` directory. 
Place both files in the same project. Then you can compile and deploy `EscrowFactory.sol`, set paramater `address_bot` to the address of your EOA.
 
Take note of the resulting address for the deployed smart contract since you'll need this for the `.env` file

### Telegram

Create a new telegram bot using [the BotFather](https://telegram.me/BotFather). All you really have to do is take note
of the token since you'll use it too on the `.env` file. 

Create a channel and add the bot as an admin. Also take note of the channel chatId. 

### Bot SetUp

First of all you should clone the repo in your local machine: 

`git clone https://github.com/cocoliso53/Peer2Paisa.git`

### `npm` and `nodejs`

Make sure you have `npm` and `nodejs` installed. 

Otherwise run

```
sudo apt update
sudo apt install -y nodejs npm
```

Then you need to add a `.env` file in `Peer2Paisa/backend/` with the following: 

```
PRIVATE_KEY=your_wallet_private_key
ALCHEMY_URL=alchemy_url
FACTORY_ADDRESS=deployed_factory_address
TELEGRAM_BOT=your_telegram_bot_token
TELEGRAM_CHANNEL=-100xxxxxxxxxx   # your channel's chat ID
```

### install dependencies

navigate to `{your_root_folder}/Peer2Paisa/backend`

and then run `npm install`

### run the bot!

run `npx ts-node ./src/bot/main.ts`

or do 

```
cd src/bot/

npx ts-node main.ts
```

That should start the bot, now you can use it on telegram!

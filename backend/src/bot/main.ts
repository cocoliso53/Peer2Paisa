import dotenv from 'dotenv'
import path from 'path'
dotenv.config({
  path: path.resolve(__dirname, '../../.env'),
})
import { Telegraf, Markup } from "telegraf";
import { createHash, randomUUID } from 'crypto';
import { isAddress } from 'viem';
import { confirmFiatReceivedFromBot, createEscrowFromBot, markAsFundedFromBot } from '../utils/botSmartContractAdapter';

type Order = {
    buyer?: {
        username: string,
        address?: string,
        chatId?: number
    },
    seller?: {
        username: string,
        address?: string,
        chatId?: number,
    }, 
    orderId: string,
    step: 'created' | 'amountSet' | 'addressSet' | 'watingCounterpart' | 'taken' | 'waitingFunding' | 'funded' | 'released' | 'dispute' | 'done' // step in progress
    status: 'active' | 'done'
    type: string
    amount?: string
    lastMessageId?: number,
    escrowAddress?: string
}

const explorerBaseURL = "https://sepolia.arbiscan.io"
const helperCreateHash = (): string => {
    const uuid = randomUUID().toString()
    return createHash('md5').update(uuid, 'utf8').digest('base64url')
}

// Temp storage
let users: string[] = []
let orders: Order[] = []
const bot = new Telegraf(process.env.TELEGRAM_BOT!);


bot.start((ctx) => {
    if (ctx.from.username) {
        users.push(ctx.from.username)
        ctx.reply("Welcome, use this bot to place and configure your orders. subscribe to https://t.me/+9W7SKA0R-Z42YmFh to see all active orders")
    } else {
        ctx.reply("Please set a username to use the bot")
    }
})

bot.command('create', ctx => {
    const username  = ctx.from.username
    if (!username) {
        ctx.reply("Can't use the bot with no username")
    }
    const activeUser = users.includes(username!)
    const activeOrder = orders.some(o =>
        o.buyer?.username === username &&
        o.seller?.username === username &&
        o.status === 'active'
    )

    if (activeOrder) {
        ctx.reply("You can't place a new order while you have one active")
    }

    if (activeUser) {
        ctx.reply(
            'Sell or buy?',
            Markup.inlineKeyboard([
                [ Markup.button.callback('Sell', 'sell'), Markup.button.callback('Buy', 'buy') ]
            ])
        )
    }
    // ctx.telegram.sendMessage(-1002641616927,"Se creó una orden")
})

bot.action(/^(sell|buy)$/, async ctx => {
  const action = ctx.match[1];

  await ctx.answerCbQuery();
  await ctx.deleteMessage();

  const m = await ctx.reply(`Enter amount to ${action} in MXN`);

  let order:Order = {
    orderId: helperCreateHash(),
    step: 'amountSet',
    status: 'active',
    lastMessageId: m.message_id,
    type: action
  }

  if (action === 'sell') {
    order = {
        ...order,
        seller: {
            username: ctx.from.username!,

        }
    }
  } else if (action === 'buy') {
    order = {
        ...order,
        buyer: {
            username: ctx.from.username!
        }
    }
  }

  console.log("order", order)
  orders.push(order)
});

// NOTE:  Maybe I should use a webhook to replace this logic
bot.command('funded', async ctx => {
    const username = ctx.from.username!
    const activeOrder = orders.find(o =>
        o.seller?.username === username &&
        o.status === 'active' &&
        o.step === 'waitingFunding'
    )

    const orderId = activeOrder?.orderId
    const escrow = activeOrder?.escrowAddress!
    const buyerChatId = activeOrder?.buyer?.chatId!
    const sellerChatId = activeOrder?.seller?.chatId!
    const buyerUsername = activeOrder?.buyer?.username
    const sellerUsername = activeOrder?.seller?.username

    const txHash = await markAsFundedFromBot(escrow)

    if (txHash) {
        await ctx.telegram.sendMessage(
            sellerChatId,
            `Escrow funded succesfully. Contact @${buyerUsername} to agree on fiat transfer details. Type /release ONLY after you have confirmed the reception of funds`
        )
        await ctx.telegram.sendMessage(
            buyerChatId,
            `Seller has funded the escrow account. Contact @${sellerUsername} to agree on fiat transfer details`
        )

        orders = orders.map((o): Order =>
                o.orderId !== orderId 
                    ? o
                    : {
                        ...o,
                        step: 'funded'
                        }
            )
    } else {
        const amount = activeOrder?.amount!
        await ctx.telegram.sendMessage(
            sellerChatId,
            `Please confrim you deposited ${amount} MXNB in the escrow address ${escrow}`
        )
    }

})

bot.command('release', async ctx => {
    const username = ctx.from.username!
    const activeOrder = orders.find(o =>
        o.seller?.username === username &&
        o.status === 'active' &&
        o.step === 'funded'
    )

    const orderId = activeOrder?.orderId
    const escrow = activeOrder?.escrowAddress!
    const buyerChatId = activeOrder?.buyer?.chatId!
    const sellerChatId = activeOrder?.seller?.chatId!

    const txHash = await confirmFiatReceivedFromBot(escrow)

    if (txHash) {
        await ctx.telegram.sendMessage(
            sellerChatId,
            "Transaction finished succesfully. Thanks for using our bot!"
        )
        await ctx.telegram.sendMessage(
            buyerChatId,
            "Transaction finished succesfully. Thanks for using our bot!"
        )

        orders = orders.map((o): Order =>
                o.orderId !== orderId 
                    ? o
                    : {
                        ...o,
                        step: 'done',
                        status: 'done'
                        }
            )
    } else {
        await ctx.telegram.sendMessage(
            sellerChatId,
            "There was an error releasing the funds. Please try again or contact support"
        )
        await ctx.telegram.sendMessage(
            buyerChatId,
            "There was an error releasing the funds. Wait for the seller to release again or contact support"
        )
    }


})

bot.on('text', async ctx => {
    
    const username = ctx.from.username!
    const activeOrder = orders.find(o =>
        (o.buyer?.username === username ||
        o.seller?.username === username ) &&
        o.status === 'active'
    )

    const orderId = activeOrder?.orderId
    const lastMessageId = activeOrder?.lastMessageId
    const orderType = activeOrder?.type

    console.log("Active order", activeOrder)

    if (!activeOrder) {
        await ctx.reply("Something went wrong")
    }

    if (activeOrder!.step === 'amountSet') {
        
        const nextMessage = orderType === 'sell' ? "Enter address for refund (if necessary)" : "Enter address to receive MXNB"
        await ctx.telegram.deleteMessage(ctx.chat.id, lastMessageId!)
        const m2 = await ctx.reply(nextMessage)
        
        const updateOrders = orders.map(o => 
            o.orderId === orderId ? {
                ...o,
                amount: ctx.message.text,
                step: "addressSet" as Order["step"],
                lastMessageId: m2.message_id
            } : o
        )

        orders = updateOrders
    } else if (activeOrder!.step === 'addressSet') {
        const makerAddress = ctx.message.text

        if (isAddress(makerAddress)) {

            const amount = activeOrder?.amount
            const orderText = orderType === 'sell' ? `Selling ${amount} MXNB` : `Buying ${amount} MXNB`
            await ctx.telegram.deleteMessage(ctx.chat.id, lastMessageId!)
            const m2 = await ctx.reply(`Order created ${orderId}`,)

            
            ctx.telegram.sendMessage(
                -1002641616927,
                orderText,
            {
                reply_markup: {
                    inline_keyboard: [
                        [
                            { text: "Take Order", callback_data: `take:${orderId}`}
                        ]
                    ]
                }
            })

            const user = {
                username: ctx.from.username!, 
                address:  ctx.message.text,
                chatId: ctx.from.id
            }

            orders = orders.map((o): Order =>
                o.orderId !== orderId 
                    ? o
                    : {
                        ...o,
                        step:"watingCounterpart",
                        lastMessageId: m2.message_id,
                        ...(orderType === "sell"
                            ? { seller: user }
                            : { buyer:  user }),
                    }
            )
        } else {
            await ctx.reply("Invalid address, please try again")
        }

        
    } else if (activeOrder!.step === 'taken') {

        if (isAddress(ctx.message.text)) {
            
            const counterpart = {
                username: ctx.from.username!,
                chatId: ctx.from.id,
                address: ctx.message.text
            }
            
            orders = orders.map((o): Order =>
                o.orderId !== orderId 
                    ? o
                    : {
                        ...o,
                        step: "waitingFunding",
                        ...(orderType === "sell"
                            ? { buyer: counterpart }
                            : { seller:  counterpart }),
                        }
            )

            const updatedOrder = orders.find(o => orderId === o.orderId)
            const escrowAddress = await createEscrowFromBot(updatedOrder?.seller?.address!, updatedOrder?.buyer?.address!, updatedOrder?.amount!)
            const escrowURL = explorerBaseURL + `/address/${escrowAddress}`

            orders = orders.map((o): Order =>
                o.orderId !== orderId 
                    ? o
                    : {
                        ...o,
                        escrowAddress: escrowAddress
                        }
            )
            

            await ctx.telegram.sendMessage(
                updatedOrder?.buyer?.chatId!,
                `Escrow created, click [here](${escrowURL}) to see the details. Wating for seller to send funds to escrow account`,
                { parse_mode: 'Markdown' }
            )

            await ctx.telegram.sendMessage(
                updatedOrder?.seller?.chatId!,
                `Escrow created, click [here](${escrowURL}) to see the details. Deposit ${updatedOrder?.amount!} MXNB and send /funded to the escrow address`,
                { parse_mode: 'Markdown' }
            )

            await ctx.telegram.sendMessage(
                updatedOrder?.seller?.chatId!,
                ` ${escrowAddress}`
            )


        } else {
            await ctx.reply("Invalid address, please try again")
        }
    }
})


bot.action(/^take:(.+)$/, async ctx => {
    const id = ctx.match[1];
    console.log("el id", id)

    const order = orders.find(o => o.orderId === id)
    const orderId = order?.orderId
    const orderType = order?.type

    const orderMakerChatId = orderType === "sell" ? order?.seller?.chatId! : order?.buyer?.chatId!
    const takerMessage = orderType === "sell" ? "Place enter the wallet where you want to receive MXNB" : "Enter address for refund (if necessary)"

    const counterpart = {
        username: ctx.from.username!,
        chatId: ctx.from.id
    }


    orders = orders.map((o): Order =>
            o.orderId !== orderId 
                ? o
                : {
                    ...o,
                    step: "taken",
                    ...(orderType === "sell"
                        ? { buyer: counterpart }
                        : { seller:  counterpart }),
                }
    )

    await ctx.answerCbQuery();

    await ctx.deleteMessage();

    await ctx.telegram.sendMessage(
        orderMakerChatId,
        `Order #${id} has been taken. Waiting for the counter part to set their address`
    )


    await ctx.telegram.sendMessage(
        ctx.from.id,
        takerMessage
    );
})



bot.launch()

process.once('SIGINT', () => bot.stop('SIGINT'))
process.once('SIGTERM', () => bot.stop('SIGTERM'))
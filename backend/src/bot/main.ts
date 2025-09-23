import dotenv from 'dotenv'
import path from 'path'
dotenv.config({
  path: path.resolve(__dirname, '../../.env'),
})
import { Telegraf, Markup, NarrowedContext } from "telegraf";
import { createHash, randomUUID } from 'crypto';
import { isAddress } from 'viem';
import { checkAmountFromBot, confirmFiatReceivedFromBot, createEscrowFromBot, markAsFundedFromBot, refundSellerFromBot } from '../utils/botSmartContractAdapter';
import { refundSeller } from '../escrow/main';
import { deltaWrapped } from '../utils/cljs-wrapper'
import { getOrderById, updateOrder, getActiveOrderByParticipant } from '../db';

type Order = {
    buyer?: {
        username: string,
        address?: string,
        chatId?: number,
        canceled?: boolean,
    },
    seller?: {
        username: string,
        address?: string,
        chatId?: number,
        canceled?: boolean,
    }, 
    orderId: string,
    participants?: string[],
    step?: 'amountSet' | 'addressSet' | 'watingCounterpart' | 'taken' | 'waitingFunding' | 'funded' | 'dispute' | 'done' | 'canceled' | 'defineAmount'
    state?: 's0' // will substitute step
    status: 'active' | 'done' | 'canceled'
    type?: string
    sell?: boolean // will replace type
    amount?: string
    lastMessageId?: number,
    escrowAddress?: string,
    orderMessageId?: number,
    range?: boolean,
    createdAt?: string,
}

const explorerBaseURL = "https://basescan.org/"
const helperCreateHash = (): string => {
    const uuid = randomUUID().toString()
    return createHash('md5').update(uuid, 'utf8').digest('base64url')
}

const rangeAmount = (input: string): boolean => {
  return /^\d+-\d+$/.test(input);
}

const s_0 = (): Order => ({
    state: 's0',
    status: 'active',
    orderId: helperCreateHash(),

})

type Effects = {
    reply?: string[],
}

type EffectsResponse = {
    reply?: number[]
}

const runEffects = async (ctx: any, effects: Effects): Promise<EffectsResponse >=> {
    const res: EffectsResponse = {}
    if (effects.reply) {
        console.log("effects.reply", effects.reply)
        res.reply = []
        for (const i in effects.reply) {
            console.log("msg", effects.reply[i])
            const m = await ctx.reply(effects.reply[i])
            res.reply.push(m.message_id)
        }
    }

    return res
}

// Temp storage
let users: string[] = []
let orders: Order[] = []
const channelId = Number(process.env.TELEGRAM_CHANNEL)
const bot = new Telegraf(process.env.TELEGRAM_BOT!);
const orderbook = process.env.ORDERBOOK_LINK


bot.start(async (ctx) => {
    if (ctx.from.username) {
        users.push(ctx.from.username)
        ctx.reply(`Welcome, use this bot to place and configure your orders. subscribe to ${orderbook} to see all active orders`)
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
    // replace with db query should take a state/order
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
})

bot.action(/^(sell|buy)$/, async ctx => {
    const action = ctx.match[1];
    
    await ctx.answerCbQuery();
    await ctx.deleteMessage();

    const m = await ctx.reply(`Enter exact amount or range (eg. 100-1000) to ${action} in XOC`);
    
    let order:Order = {
        orderId: helperCreateHash(),
        step: 'amountSet',
        status: 'active',
        lastMessageId: m.message_id,
        type: action
    }
    
    const newOrder = s_0()
    const { state:dataAumentada, effects} =  deltaWrapped(newOrder, 
        {
            event: action,
            data: {
                username: ctx.from.username!,
                messageId: m.message_id!
            }
        }
    )
    console.log('Data aumentada:', dataAumentada);
    console.log('Efectos', effects)

    const { reply } = await runEffects(ctx, effects)

    console.log("replyEffect", reply)

    if (action === 'sell') {
        order = {
            ...order,
            seller: {
                username: ctx.from.username!
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


    const { state } = deltaWrapped(
        dataAumentada,
        {
            event: "setLastMessageId",
            data: { messageId: reply![0] }
        }
    )
    const up = await updateOrder(state)
    console.log("dbCouch", up)
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
            `Please confrim you deposited ${amount} XOC in the escrow address ${escrow}`
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
    const explorerUrl = explorerBaseURL + `tx/${txHash}`
    const successMEssage = `Transaction [completed](${explorerUrl})! Thanks for using our bot!`

    if (txHash) {
        await ctx.telegram.sendMessage(
            sellerChatId,
            successMEssage,
            { parse_mode: 'Markdown' }
        )
        await ctx.telegram.sendMessage(
            buyerChatId,
            successMEssage,
            { parse_mode: 'Markdown' }
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

/*
bot.command('release', async ctx => {
    const username = ctx.from.username!
    const activeOrder = orders.find(o =>
        o.seller?.username === username &&
        o.status === 'active' &&
        o.step === 'funded'
    )

    const buyerChatId = activeOrder?.buyer?.chatId!
    
    await ctx.telegram.sendMessage(
        buyerChatId, 
        "You want to receive XOC or USDT", 
        Markup.inlineKeyboard([
                [ Markup.button.callback('XOC', 'XOC'), Markup.button.callback('USDT', 'USDT') ]
            ]
        )
    )

})
*/

bot.command('help', async ctx => {
    await ctx.reply("Please contact @cuaucortes for assistance")
})

bot.command('cancel', async ctx => {
    const username = ctx.from.username!
    const activeOrder = orders.find(o =>
        (o.buyer?.username === username ||
        o.seller?.username === username ) &&
        o.status === 'active'
    )
    const orderId = activeOrder?.orderId

    if(!activeOrder) {
        ctx.reply("You have no active orders")
    }


    if (activeOrder && (
        activeOrder.step === 'addressSet' ||
        activeOrder.step === 'amountSet' ||
        activeOrder.step === 'watingCounterpart')) {

            orders = orders.map((o): Order =>
                o.orderId !== orderId 
                   ? o
                   : {
                    ...o,
                    step: 'canceled',
                    status: 'done'
                }
            )
            
            await ctx.reply("Order canceled succesfully")
            const orderMessageId = activeOrder.orderMessageId
            if (orderMessageId) {
                await ctx.telegram.deleteMessage(
                    channelId,
                    orderMessageId
                )
            }
        } else 
        
        if (activeOrder?.seller?.username === username) {
            switch (activeOrder.step) {

                case 'taken':
                case 'waitingFunding':
                    orders = orders.map((o): Order =>
                    o.orderId !== orderId 
                        ? o
                        : {
                            ...o,
                            step: 'canceled',
                            status: 'done'
                        }
                    )

                    await ctx.reply("Order canceled succesfully")
                    await ctx.telegram.sendMessage(activeOrder.buyer?.chatId!, "Seller canceled the order")
                    break
                    
                case 'funded':
                    await ctx.reply("Only buyer can initiate order cancelation right now")
                    break
                    
                default:
                    await ctx.reply("Can't cancel order at current stage")

            }
        } else 

        if (activeOrder?.buyer?.username === username) {
            switch (activeOrder.step) {

                case 'taken':
                case 'waitingFunding':
                case 'funded':
                    orders = orders.map((o): Order =>
                    o.orderId !== orderId 
                        ? o
                        : {
                            ...o,
                            step: 'canceled',
                            status: 'done'
                        }
                    )

                    await ctx.reply("Order canceled succesfully")
                    await ctx.telegram.sendMessage(activeOrder.buyer?.chatId!, "Buyer canceled the order")

                    const escrowAddress = activeOrder.escrowAddress
                    const amount = escrowAddress ? await checkAmountFromBot(escrowAddress) : null
                    if (amount && amount > 0) {
                        await markAsFundedFromBot(escrowAddress!)
                        // It would be better to wait for receipt once tx is confirmed
                        await new Promise(resolve => setTimeout(resolve, 5000));
                        const txHashRefund = await refundSellerFromBot(escrowAddress!)
                        const explorerUrl = explorerBaseURL + `/tx/${txHashRefund}`
                        const nextMessage = txHashRefund ? `Funds have been returned on tx ${explorerUrl}` : `Something went wrong, please contact support`
                        ctx.telegram.sendMessage(activeOrder.seller?.chatId!, nextMessage)
                        ctx.telegram.sendMessage(activeOrder.buyer.chatId!, "Order canceled")
                    }
                    
                    break
                    
                    
                default:
                    await ctx.reply("Can't cancel order at current stage")
                    
            }
        }
    }
)

bot.on('text', async ctx => {
    
    const username = ctx.from.username!
    const activeOrder = orders.find(o =>
        (o.buyer?.username === username ||
        o.seller?.username === username ) &&
        o.status === 'active'
    )

    // Testing fsm and couchdb
    const { docs } = await getActiveOrderByParticipant(username)
    const activeCouch = docs[0]
    console.log("activeCouch",activeCouch)


    const orderId = activeOrder?.orderId
    const lastMessageId = activeOrder?.lastMessageId
    const orderType = activeOrder?.type
    
    if (!activeOrder) {
        await ctx.reply("Something went wrong")
    }

    const data = {
        text: ctx.message.text,
    }

    const user = {
        username: ctx.from.username!, 
        address:  ctx.message.text,
        chatId: ctx.from.id
    }


    let eventObject = (() => {
        switch (activeCouch.state) {
            case 'waitingNewOrderAmount':
                return { event: "setAmount", data }
            case 'waitingSetAddress':
                return { 
                    event: "setAddress", 
                    data : {
                        text: ctx.message.text,
                        user: user
                    }
                }
            case 'orderTaken': 
                return { event: "counterpartyDetails", counterpart: { username: ctx.from.username!, chatId: ctx.from.id, address: data} }
            case 'defineAmount':    
                return { event: "setAmount", data }
        }
    })()

    const { state:newState } = deltaWrapped(activeCouch, eventObject)
    await updateOrder(newState)
    console.log("newState",newState)


    if (activeOrder!.step === 'amountSet') {
        
        const nextMessage = orderType === 'sell' ? "Enter address for refund (if necessary)" : "Enter address to receive XOC"
        await ctx.telegram.deleteMessage(ctx.chat.id, lastMessageId!)
        const m2 = await ctx.reply(nextMessage)
        const isRange = rangeAmount(ctx.message.text)
        
        const updateOrders = orders.map(o => 
            o.orderId === orderId ? {
                ...o,
                amount: ctx.message.text,
                range: isRange,
                step: "addressSet" as Order["step"],
                lastMessageId: m2.message_id
            } : o
        )

        orders = updateOrders
    } else if (activeOrder!.step === 'addressSet') {
        const makerAddress = ctx.message.text

        if (isAddress(makerAddress)) {

            const amount = activeOrder?.amount
            const orderText = orderType === 'sell' ? `Selling ${amount} XOC` : `Buying ${amount} XOC`
            await ctx.telegram.deleteMessage(ctx.chat.id, lastMessageId!)
            const m2 = await ctx.reply(`Order created ${orderId}`,)

            
            const orderMessage = await ctx.telegram.sendMessage(
                channelId,
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

            orders = orders.map((o): Order =>
                o.orderId !== orderId 
                    ? o
                    : {
                        ...o,
                        step: 'watingCounterpart',
                        orderMessageId: orderMessage.message_id,
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
                `Escrow created, click [here](${escrowURL}) to see the details. Wating for seller to fund escrow contract`,
                { parse_mode: 'Markdown' }
            )

            await ctx.telegram.sendMessage(
                updatedOrder?.seller?.chatId!,
                `Escrow created, click [here](${escrowURL}) to see the details. Deposit ${updatedOrder?.amount!} XOC and then type /funded to continue`,
                { parse_mode: 'Markdown' }
            )

            await ctx.telegram.sendMessage(
                updatedOrder?.seller?.chatId!,
                ` ${escrowAddress}`
            )


        } else {
            await ctx.reply("Invalid address, please try again")
        }
    } else if (activeOrder!.step === 'defineAmount') {
        const nextMessage = orderType === 'sell' ? "Please enter the wallet where you want to receive XOC" : "Enter address for refund (if necessary)"
        const m = await ctx.reply(nextMessage)
        
        orders = orders.map((o): Order =>
            o.orderId !== orderId 
                ? o
                : {
                    ...o,
                    lastMessageId: m.message_id,
                    amount: ctx.message.text,
                    step: 'taken',
                }
        )
    }
})


/*
bot.action(/^(XOC|USDT)/, async ctx => {
    const token = ctx.match[0]
    const username = ctx.from.username!
    const activeOrder = orders.find(o =>
        o.buyer?.username === username &&
        o.status === 'active' &&
        o.step === 'funded'
    )

    const orderId = activeOrder?.orderId
    const escrow = activeOrder?.escrowAddress!
    const buyerChatId = activeOrder?.buyer?.chatId!
    const sellerChatId = activeOrder?.seller?.chatId!

    await ctx.answerCbQuery();

    await ctx.deleteMessage();

    const txHash = token === "XOC" ? await confirmFiatReceivedFromBot(escrow) : await swapAndSendFromBot(escrow)
    const explorerUrl = explorerBaseURL + `/tx/${txHash}`

    if (txHash) {
        await ctx.telegram.sendMessage(
            sellerChatId,
            "Transaction finished succesfully. Thanks for using our bot!"
        )
        await ctx.telegram.sendMessage(
            buyerChatId,
            `txHash: ${explorerUrl}`
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
*/

bot.action(/^take:(.+)$/, async ctx => {
    const id = ctx.match[1];

    const order = orders.find(o => o.orderId === id)
    const orderId = order?.orderId
    const orderType = order?.type
    const amountRange = order?.range

    const counterpart = {
        username: ctx.from.username!,
        chatId: ctx.from.id
    }

    let takerMessage: string

    if (amountRange) {
        const range = order.amount
        takerMessage = `Select an amount between ${range}`
        
        orders = orders.map((o): Order =>
            o.orderId !== orderId 
                ? o
                : {
                    ...o,
                    step: 'defineAmount',
                    ...(orderType === "sell"
                        ? { buyer: counterpart }
                        : { seller:  counterpart }),
                }
        )
    } else {
        takerMessage = orderType === "sell" ? "Please enter the wallet where you want to receive XOC" : "Enter address for refund (if necessary)"
        
        orders = orders.map((o): Order =>
            o.orderId !== orderId 
                ? o
                : {
                    ...o,
                    step: 'taken',
                    ...(orderType === "sell"
                        ? { buyer: counterpart }
                        : { seller:  counterpart }),
                }
        )
    }

    const orderMakerChatId = orderType === "sell" ? order?.seller?.chatId! : order?.buyer?.chatId!

    await ctx.answerCbQuery();

    await ctx.deleteMessage();

    await ctx.telegram.sendMessage(
        orderMakerChatId,
        `Order #${id} has been taken. Waiting for the counter part to enter details`
    )


    await ctx.telegram.sendMessage(
        ctx.from.id,
        takerMessage
    );
})

bot.on('channel_post', ctx => {
  console.log('Channel ID:', ctx.channelPost.chat.id);
});


bot.launch()

process.once('SIGINT', () => bot.stop('SIGINT'))
process.once('SIGTERM', () => bot.stop('SIGTERM'))
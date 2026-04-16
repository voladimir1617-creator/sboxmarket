package com.sboxmarket.service

import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.PriceHistoryRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Slf4j
class ItemService {

    @Autowired ItemRepository itemRepository
    @Autowired PriceHistoryRepository priceHistoryRepository

    List<Item> getAll() {
        itemRepository.findAll()
    }

    Item getById(Long id) {
        itemRepository.findById(id).orElseThrow { new NotFoundException("Item", id) }
    }

    List<Item> search(String q, String category, String rarity, String sort,
                      BigDecimal minPrice, BigDecimal maxPrice) {

        List<Item> items

        if (q) {
            items = itemRepository.searchByName(q)
        } else if (category && category != 'All' && rarity && rarity != 'All') {
            items = itemRepository.findByCategoryAndRarity(category, rarity)
        } else if (category && category != 'All') {
            items = itemRepository.findByCategory(category)
        } else if (rarity && rarity != 'All') {
            items = itemRepository.findByRarity(rarity)
        } else {
            items = itemRepository.findAll()
        }

        // price filter
        if (minPrice != null) items = items.findAll { it.lowestPrice >= minPrice }
        if (maxPrice != null) items = items.findAll { it.lowestPrice <= maxPrice }

        // sort — classic Groovy switch on a mutable copy so we never touch a
        // repository-backed list (same reliability fix applied to ListingService)
        def sorted = new ArrayList<Item>(items)
        switch (sort) {
            case 'price_asc':
                sorted.sort { a, b -> a.lowestPrice <=> b.lowestPrice }; break
            case 'price_desc':
                sorted.sort { a, b -> b.lowestPrice <=> a.lowestPrice }; break
            case 'popular':
                sorted.sort { a, b -> b.totalSold  <=> a.totalSold  }; break
            case 'rarity':
                sorted.sort { a, b -> a.supply     <=> b.supply     }; break
            case 'newest':
                sorted.sort { a, b -> b.createdAt  <=> a.createdAt  }; break
            default:
                sorted.sort { a, b -> b.lowestPrice <=> a.lowestPrice }; break
        }
        sorted
    }

    List<PriceHistory> getPriceHistory(Long itemId) {
        priceHistoryRepository.findByItemIdOrdered(itemId)
    }

    @Transactional
    Item save(Item item) {
        itemRepository.save(item)
    }

    Map<String, Object> getStats() {
        def items = itemRepository.findAll()
        [
            totalItems   : items.size(),
            limitedCount : items.count { it.rarity == 'Limited' },
            floorPrice   : items.min { it.lowestPrice }?.lowestPrice ?: 0,
            highestPrice : items.max { it.lowestPrice }?.lowestPrice ?: 0,
            categories   : items.groupBy { it.category }.collectEntries { k, v -> [k, v.size()] }
        ]
    }
}

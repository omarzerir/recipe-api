package com.zerir.recipe_api.service;

import com.zerir.recipe_api.model.Category;
import com.zerir.recipe_api.model.Ingredient;
import com.zerir.recipe_api.model.Recipe;
import com.zerir.recipe_api.repository.CategoryRepository;
import com.zerir.recipe_api.repository.IngredientRepository;
import com.zerir.recipe_api.repository.RecipeRepository;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataInitializerService {
    private final RecipeRepository recipeRepository;
    private final IngredientRepository ingredientRepository;
    private final CategoryRepository categoryRepository;
    private final ResourceLoader resourceLoader;

    private static final int MAX_RECIPES = 500;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeData() {
        if (recipeRepository.count() > 0) {
            log.info("Database already contains {} recipes. Skipping initialization.", recipeRepository.count());
            return; // Skip if the database is not empty
        }

        log.info("Starting data initialization. Loading up to {} recipes.", MAX_RECIPES);

        // Cache for categories and ingredients to avoid repeated database lookups
        Map<String, Category> categoryCache = new HashMap<>();
        Map<String, Ingredient> ingredientCache = new HashMap<>();

        Resource resource = resourceLoader.getResource("classpath:RAW_recipes.csv");

        try (CSVReader csvReader = new CSVReader(new InputStreamReader(resource.getInputStream()))) {
            csvReader.skip(1); // Skip header
            String[] record;
            int count = 0;
            List<Recipe> recipeBatch = new ArrayList<>();

            while ((record = csvReader.readNext()) != null && count < MAX_RECIPES) {
                try {
                    Recipe recipe = processRecipeRecord(record, categoryCache, ingredientCache);
                    if (recipe != null) {
                        recipeBatch.add(recipe);
                        count++;

                        // Process in batches of 50 to avoid memory issues
                        if (count % 50 == 0) {
                            recipeRepository.saveAll(recipeBatch);
                            recipeBatch.clear();
                            log.info("Processed {} recipes", count);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Error processing recipe record: {}", Arrays.toString(record), e);
                    // Continue with next record
                }
            }

            // Save any remaining recipes
            if (!recipeBatch.isEmpty()) {
                recipeRepository.saveAll(recipeBatch);
                log.info("Processed {} recipes in total", count);
            }

            log.info("Data initialization completed successfully. Loaded {} recipes.", count);
        } catch (IOException | CsvValidationException e) {
            log.error("Failed to initialize data from CSV", e);
            throw new RuntimeException("Failed to initialize data from CSV", e);
        }
    }

    private Recipe processRecipeRecord(String[] record, Map<String, Category> categoryCache,
                                       Map<String, Ingredient> ingredientCache) {
        // Safety check for array length
        if (record.length < 12) {
            log.warn("Skipping record with insufficient columns: {}", Arrays.toString(record));
            return null;
        }

        try {
            // Map CSV: name, minutes, tags, ingredients, steps
            String title = record[1];
            int cookTime = Integer.parseInt(record[3]);
            String tags = record[8];
            String ingredients = record[10];
            String description = record[11];

            // Category: Use first tag
            String categoryName = extractFirstTag(tags);
            Category category = getCategoryFromCacheOrDb(categoryName, categoryCache);

            // Ingredients
            List<Ingredient> ingredientList = processIngredients(ingredients, ingredientCache);

            // Recipe
            Recipe recipe = new Recipe();
            recipe.setTitle(title);
            recipe.setDescription(description);
            recipe.setCookTime(cookTime);
            recipe.setCreatedAt(LocalDateTime.now());
            recipe.setCategory(category);
            recipe.setIngredients(ingredientList);

            return recipe;
        } catch (NumberFormatException e) {
            log.warn("Failed to parse cook time for recipe: {}", record[1], e);
            return null;
        }
    }

    private String extractFirstTag(String tags) {
        return Arrays.stream(tags.replaceAll("[\\[\\]'\"]", "").split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .findFirst()
                .orElse("General");
    }

    private Category getCategoryFromCacheOrDb(String categoryName, Map<String, Category> categoryCache) {
        return categoryCache.computeIfAbsent(categoryName, name -> categoryRepository.findByName(name)
                .orElseGet(() -> {
                    Category newCategory = new Category();
                    newCategory.setName(name);
                    return categoryRepository.save(newCategory);
                }));
    }

    private List<Ingredient> processIngredients(String ingredientsStr, Map<String, Ingredient> ingredientCache) {
        List<Ingredient> ingredientList = new ArrayList<>();

        for (String ingredientName : ingredientsStr.replaceAll("[\\[\\]'\"]", "").split(",")) {
            ingredientName = ingredientName.trim();
            if (!ingredientName.isEmpty()) {
                Ingredient ingredient = getIngredientFromCacheOrDb(ingredientName, ingredientCache);
                ingredientList.add(ingredient);
            }
        }

        return ingredientList;
    }

    private Ingredient getIngredientFromCacheOrDb(String ingredientName, Map<String, Ingredient> ingredientCache) {
        return ingredientCache.computeIfAbsent(ingredientName, name -> ingredientRepository.findByName(name)
                .orElseGet(() -> {
                    Ingredient newIngredient = new Ingredient();
                    newIngredient.setName(name);
                    return ingredientRepository.save(newIngredient);
                }));
    }
}
